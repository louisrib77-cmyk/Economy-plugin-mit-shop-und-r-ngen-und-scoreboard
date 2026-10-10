package de.louis.economy;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Zugriff auf das Geld im JobsPlugin per Reflection
 * (DataManager.get(uuid) -> PlayerData.getMoney()/setMoney()).
 */
final class JobsBridge {

    private final Plugin jobs;
    private final Object dataManager;
    private final Method get;
    private final Method save;
    private final Method unload;
    private final Method getMoney;
    private final Method setMoney;

    private JobsBridge(Plugin jobs, Object dataManager, Method get, Method save, Method unload,
                       Method getMoney, Method setMoney) {
        this.jobs = jobs;
        this.dataManager = dataManager;
        this.get = get;
        this.save = save;
        this.unload = unload;
        this.getMoney = getMoney;
        this.setMoney = setMoney;
    }

    static JobsBridge create(Plugin owner) {
        Plugin jobs = Bukkit.getPluginManager().getPlugin("JobsPlugin");
        if (jobs == null || !jobs.isEnabled()) return null;
        try {
            Object dm = jobs.getClass().getMethod("getDataManager").invoke(jobs);
            Method get = dm.getClass().getMethod("get", UUID.class);
            Method save = dm.getClass().getMethod("save", UUID.class);
            Method unload = dm.getClass().getMethod("unload", UUID.class);
            Class<?> dataClass = get.getReturnType();
            Method getMoney = dataClass.getMethod("getMoney");
            Method setMoney = dataClass.getMethod("setMoney", double.class);
            for (Method m : new Method[]{get, save, unload, getMoney, setMoney}) {
                try {
                    m.setAccessible(true);
                } catch (RuntimeException ignored) {
                }
            }
            return new JobsBridge(jobs, dm, get, save, unload, getMoney, setMoney);
        } catch (ReflectiveOperationException e) {
            owner.getLogger().severe("JobsPlugin konnte nicht angebunden werden: " + e);
            return null;
        }
    }

    double getMoney(UUID id) {
        try {
            Object data = get.invoke(dataManager, id);
            return data == null ? 0.0 : (double) getMoney.invoke(data);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    void setMoney(UUID id, double value) {
        try {
            Object data = get.invoke(dataManager, id);
            setMoney.invoke(data, value);
            save.invoke(dataManager, id);
            if (Bukkit.getPlayer(id) == null) {
                unload.invoke(dataManager, id); // Offline-Spieler nicht im Speicher halten
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    File playersFolder() {
        return new File(jobs.getDataFolder(), "players");
    }

    boolean hasData(UUID id) {
        return Bukkit.getPlayer(id) != null || new File(playersFolder(), id + ".yml").exists();
    }
}
