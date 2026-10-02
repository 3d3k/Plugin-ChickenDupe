package com.zhouyi.mc3d3k;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Chicken;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * ChickenDupe (Folia 版)
 * - 手持物品右键成年鸡：消耗经验等级 -> 绑定 -> 立刻掉落一次，之后每 DropInterval 秒掉落一次（区块未加载也会掉落）
 * - /dupe：消耗 1/4 级经验复制手中物品，每日无限次
 * - 数据保存在 SQLite
 */
public final class Dupe extends JavaPlugin implements Listener {

    // ==================== 配置 ====================
    private long intervalMs;
    private int dropAmount;
    private int bindCostLevels;
    private double copyCostFraction;
    private boolean copyWholeStack;

    // ==================== 数据 ====================
    private final Map<UUID, Binding> bindings = new ConcurrentHashMap<>();
    private Database db;

    /** 一只被绑定的鸡 */
    private static final class Binding {
        final UUID chicken;
        volatile UUID owner;
        volatile ItemStack item;
        volatile String world;
        volatile double x, y, z;
        volatile long nextDrop;

        Binding(UUID chicken, UUID owner, ItemStack item, String world,
                double x, double y, double z, long nextDrop) {
            this.chicken = chicken;
            this.owner = owner;
            this.item = item;
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.nextDrop = nextDrop;
        }

        void setLocation(Location loc) {
            if (loc.getWorld() != null) this.world = loc.getWorld().getName();
            this.x = loc.getX();
            this.y = loc.getY();
            this.z = loc.getZ();
        }
    }

    // ==================== 生命周期 ====================

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfig();
        loadConfigCache();

        getDataFolder().mkdirs();
        db = new Database(new File(getDataFolder(), "chickendupe.db"));
        try {
            db.init();
            for (Binding b : db.loadAll()) {
                bindings.put(b.chicken, b);
            }
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "初始化 SQLite 失败，插件将被禁用", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("dupe")).setExecutor(new DupeCommand());

        // 每 5 秒检查一次哪些鸡到了掉落时间（异步，只读内存）
        getServer().getAsyncScheduler().runAtFixedRate(this, task -> tick(), 5, 5, TimeUnit.SECONDS);

        getLogger().info("ChickenDupe v" + getPluginMeta().getVersion()
                + " 已启用（Folia），已载入 " + bindings.size() + " 只绑定的鸡");
    }

    @Override
    public void onDisable() {
        getServer().getAsyncScheduler().cancelTasks(this);
        if (db != null) db.close();
    }

    private void loadConfigCache() {
        intervalMs = Math.max(1, getConfig().getLong("DropInterval", 300)) * 1000L;
        dropAmount = Math.max(1, getConfig().getInt("DropAmount", 1));
        bindCostLevels = Math.max(0, getConfig().getInt("BindCostLevels", 1));
        copyCostFraction = Math.max(0.0, getConfig().getDouble("CopyCostFraction", 0.25));
        copyWholeStack = getConfig().getBoolean("CopyWholeStack", true);
    }

    // ==================== 定时掉落 ====================

    private void tick() {
        long now = System.currentTimeMillis();
        List<Binding> due = new ArrayList<>();
        for (Binding b : bindings.values()) {
            if (b.nextDrop <= now) {
                b.nextDrop = now + intervalMs; // 先推后，避免重复触发
                due.add(b);
            }
        }
        if (due.isEmpty()) return;

        db.saveAll(due);
        for (Binding b : due) {
            try {
                dispatchDrop(b);
            } catch (Throwable t) {
                getLogger().log(Level.WARNING, "处理鸡 " + b.chicken + " 的掉落时出错", t);
            }
        }
    }

    /** 决定在哪个线程、以什么方式掉落 */
    private void dispatchDrop(Binding b) {
        // 1) 鸡当前已加载：交给实体调度器，在鸡所属区域线程里掉落
        Entity ent = Bukkit.getEntity(b.chicken);
        if (ent != null) {
            scheduleEntityDrop(b, ent);
            return;
        }

        // 2) 鸡所在区块未加载：异步加载区块，再切到该区域线程掉落
        World world = Bukkit.getWorld(b.world);
        if (world == null) return; // 世界没加载，跳过本轮
        final int cx = ((int) Math.floor(b.x)) >> 4;
        final int cz = ((int) Math.floor(b.z)) >> 4;

        world.getChunkAtAsync(cx, cz, false).whenComplete((chunk, err) -> {
            if (err != null) {
                getLogger().log(Level.WARNING, "加载区块失败: " + world.getName() + " " + cx + "," + cz, err);
                return;
            }
            if (chunk == null) { // 区块不存在，鸡不可能还在
                removeBinding(b.chicken);
                return;
            }
            Bukkit.getRegionScheduler().run(this, world, cx, cz, st -> dropAtStoredLocation(b, world, chunk));
        });
    }

    /** 在存储的坐标（区域线程内）掉落 */
    private void dropAtStoredLocation(Binding b, World world, Chunk chunk) {
        Entity ent = Bukkit.getEntity(b.chicken);
        if (ent != null) { // 区块加载后鸡出现了，走实体路径
            scheduleEntityDrop(b, ent);
            return;
        }
        if (chunk.isEntitiesLoaded()) { // 实体已加载完但没有这只鸡：鸡已不存在
            removeBinding(b.chicken);
            return;
        }
        doDrop(b, new Location(world, b.x, b.y, b.z));
    }

    private void scheduleEntityDrop(Binding b, Entity ent) {
        ent.getScheduler().run(this, st -> {
            Location loc = ent.getLocation();
            b.setLocation(loc);
            db.updateLocation(b);
            doDrop(b, loc);
        }, null);
    }

    private void doDrop(Binding b, Location loc) {
        ItemStack drop = b.item.clone();
        drop.setAmount(Math.max(1, Math.min(dropAmount, drop.getMaxStackSize())));
        loc.getWorld().dropItemNaturally(loc, drop);
    }

    private void removeBinding(UUID chicken) {
        if (bindings.remove(chicken) != null) {
            db.delete(chicken);
        }
    }

    // ==================== 事件 ====================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return; // 避免主副手触发两次
        if (!(event.getRightClicked() instanceof Chicken chicken)) return;
        if (!chicken.isAdult()) return;

        Player player = event.getPlayer();
        if (!player.hasPermission("chickendupe.use")) return;

        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) return;

        event.setCancelled(true); // 这次右键视为「绑定」，不触发喂食等原版行为

        // 已经绑定了同样的物品：不重复扣费
        Binding existing = bindings.get(chicken.getUniqueId());
        if (existing != null && existing.item.isSimilar(hand)) {
            player.sendMessage(Component.text("这只鸡已经绑定了该物品。", NamedTextColor.YELLOW));
            return;
        }

        // 经验检查
        if (player.getLevel() < bindCostLevels) {
            player.sendMessage(Component.text("经验不足！绑定需要 " + bindCostLevels + " 级经验。", NamedTextColor.RED));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
            return;
        }
        player.setLevel(player.getLevel() - bindCostLevels);

        ItemStack one = hand.clone();
        one.setAmount(1);
        Location loc = chicken.getLocation();
        Binding b = new Binding(chicken.getUniqueId(), player.getUniqueId(), one,
                loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ(),
                System.currentTimeMillis() + intervalMs);
        bindings.put(b.chicken, b);
        db.save(b);

        player.playSound(loc, Sound.UI_BUTTON_CLICK, 1.0f, 1.0f);
        player.sendMessage(Component.text("绑定成功！已消耗 " + bindCostLevels + " 级经验，每 "
                + (intervalMs / 1000) + " 秒掉落一次。", NamedTextColor.GREEN));

        // 改名 + 立刻掉落一次（在鸡所属线程执行）
        chicken.getScheduler().run(this, st -> {
            chicken.customName(Component.text("[物品] ", NamedTextColor.GREEN).append(displayName(one)));
            chicken.setCustomNameVisible(true);
            doDrop(b, chicken.getLocation());
        }, null);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        if (event.getEntity() instanceof Chicken) {
            removeBinding(event.getEntity().getUniqueId());
        }
    }

    /** 区块卸载前记录鸡的最新位置，之后即使区块未加载也能按坐标掉落 */
    @EventHandler
    public void onEntitiesUnload(EntitiesUnloadEvent event) {
        for (Entity e : event.getEntities()) {
            if (!(e instanceof Chicken)) continue;
            Binding b = bindings.get(e.getUniqueId());
            if (b == null) continue;
            b.setLocation(e.getLocation());
            db.updateLocation(b);
        }
    }

    private Component displayName(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            Component name = meta.displayName();
            if (name != null) return name.colorIfAbsent(NamedTextColor.GOLD);
        }
        return Component.translatable(item.getType().translationKey(), NamedTextColor.GOLD);
    }

    // ==================== /dupe ====================

    private final class DupeCommand implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("该命令只能由玩家执行。");
                return true;
            }
            if (!player.hasPermission("chickendupe.use")) {
                player.sendMessage(Component.text("你没有权限使用该命令。", NamedTextColor.RED));
                return true;
            }

            ItemStack hand = player.getInventory().getItemInMainHand();
            if (hand.getType().isAir()) {
                player.sendMessage(Component.text("请手持要复制的物品！", NamedTextColor.RED));
                return true;
            }

            int level = player.getLevel();
            int cost = Math.max(1, (int) Math.ceil(xpToNextLevel(level) * copyCostFraction));
            int total = totalXp(player);
            if (total < cost) {
                player.sendMessage(Component.text("经验不足！复制一次需要 " + cost + " 点经验。", NamedTextColor.RED));
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
                return true;
            }
            setTotalXp(player, total - cost);

            ItemStack copy = hand.clone();
            if (!copyWholeStack) copy.setAmount(1);
            for (ItemStack left : player.getInventory().addItem(copy).values()) {
                player.getWorld().dropItem(player.getLocation(), left);
            }
            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
            player.sendMessage(Component.text("复制成功！消耗 " + cost + " 点经验。", NamedTextColor.GREEN));
            return true;
        }
    }

    // ==================== 经验工具（原版公式） ====================

    /** 从 0 级升到 level 级所需的总经验 */
    private static int xpForLevel(int level) {
        if (level <= 16) return level * level + 6 * level;
        if (level <= 31) return (int) (2.5 * level * level - 40.5 * level + 360);
        return (int) (4.5 * level * level - 162.5 * level + 2220);
    }

    /** 当前等级升到下一级所需经验 */
    private static int xpToNextLevel(int level) {
        if (level <= 15) return 2 * level + 7;
        if (level <= 30) return 5 * level - 38;
        return 9 * level - 158;
    }

    private static int totalXp(Player p) {
        int lv = p.getLevel();
        return xpForLevel(lv) + Math.round(p.getExp() * xpToNextLevel(lv));
    }

    private static void setTotalXp(Player p, int total) {
        int lv = 0;
        while (xpForLevel(lv + 1) <= total) lv++;
        int into = total - xpForLevel(lv);
        p.setLevel(lv);
        p.setExp(Math.min(0.999f, into / (float) xpToNextLevel(lv)));
    }

    // ==================== SQLite ====================

    /** 所有数据库操作都在同一个后台线程上串行执行 */
    private final class Database {
        private final File file;
        private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ChickenDupe-SQLite");
            t.setDaemon(true);
            return t;
        });
        private Connection conn;

        Database(File file) {
            this.file = file;
        }

        void init() throws SQLException {
            conn = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA synchronous=NORMAL");
                st.execute("CREATE TABLE IF NOT EXISTS bindings ("
                        + "chicken_uuid TEXT PRIMARY KEY,"
                        + "owner_uuid   TEXT NOT NULL,"
                        + "item         BLOB NOT NULL,"
                        + "world        TEXT NOT NULL,"
                        + "x REAL NOT NULL, y REAL NOT NULL, z REAL NOT NULL,"
                        + "next_drop    INTEGER NOT NULL)");
            }
        }

        List<Binding> loadAll() throws SQLException {
            List<Binding> list = new ArrayList<>();
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT * FROM bindings")) {
                while (rs.next()) {
                    try {
                        list.add(new Binding(
                                UUID.fromString(rs.getString("chicken_uuid")),
                                UUID.fromString(rs.getString("owner_uuid")),
                                ItemStack.deserializeBytes(rs.getBytes("item")),
                                rs.getString("world"),
                                rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z"),
                                rs.getLong("next_drop")));
                    } catch (Exception e) {
                        getLogger().log(Level.WARNING, "跳过一条损坏的绑定记录", e);
                    }
                }
            }
            return list;
        }

        void save(Binding b) {
            saveAll(List.of(b));
        }

        void saveAll(Collection<Binding> list) {
            final List<Binding> snapshot = new ArrayList<>(list);
            submit(() -> {
                String sql = "INSERT OR REPLACE INTO bindings"
                        + "(chicken_uuid, owner_uuid, item, world, x, y, z, next_drop) VALUES(?,?,?,?,?,?,?,?)";
                boolean oldAuto = conn.getAutoCommit();
                conn.setAutoCommit(false);
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    for (Binding b : snapshot) {
                        ps.setString(1, b.chicken.toString());
                        ps.setString(2, b.owner.toString());
                        ps.setBytes(3, b.item.serializeAsBytes());
                        ps.setString(4, b.world);
                        ps.setDouble(5, b.x);
                        ps.setDouble(6, b.y);
                        ps.setDouble(7, b.z);
                        ps.setLong(8, b.nextDrop);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                    conn.commit();
                } catch (SQLException e) {
                    conn.rollback();
                    throw e;
                } finally {
                    conn.setAutoCommit(oldAuto);
                }
            });
        }

        void updateLocation(Binding b) {
            final String id = b.chicken.toString();
            final String world = b.world;
            final double x = b.x, y = b.y, z = b.z;
            submit(() -> {
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE bindings SET world=?, x=?, y=?, z=? WHERE chicken_uuid=?")) {
                    ps.setString(1, world);
                    ps.setDouble(2, x);
                    ps.setDouble(3, y);
                    ps.setDouble(4, z);
                    ps.setString(5, id);
                    ps.executeUpdate();
                }
            });
        }

        void delete(UUID chicken) {
            final String id = chicken.toString();
            submit(() -> {
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM bindings WHERE chicken_uuid=?")) {
                    ps.setString(1, id);
                    ps.executeUpdate();
                }
            });
        }

        private void submit(SqlTask task) {
            try {
                executor.execute(() -> {
                    try {
                        task.run();
                    } catch (Exception e) {
                        getLogger().log(Level.WARNING, "SQLite 操作失败", e);
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException ignored) {
                // 已关闭
            }
        }

        void close() {
            executor.shutdown();
            try {
                executor.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            try {
                if (conn != null) conn.close();
            } catch (SQLException ignored) {
            }
        }
    }

    @FunctionalInterface
    private interface SqlTask {
        void run() throws Exception;
    }
}
