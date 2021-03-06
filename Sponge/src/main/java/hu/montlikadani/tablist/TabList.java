package hu.montlikadani.tablist;

import org.spongepowered.api.Sponge;
import org.spongepowered.api.entity.living.player.Player;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.game.GameReloadEvent;
import org.spongepowered.api.event.game.state.GameInitializationEvent;
import org.spongepowered.api.event.game.state.GamePreInitializationEvent;
import org.spongepowered.api.event.game.state.GameStartedServerEvent;
import org.spongepowered.api.event.game.state.GameStoppingEvent;
import org.spongepowered.api.plugin.Dependency;
import org.spongepowered.api.plugin.Plugin;
import org.spongepowered.api.plugin.PluginContainer;
import org.spongepowered.api.scheduler.Task;
import org.spongepowered.api.scoreboard.Scoreboard;

import com.google.inject.Inject;

import hu.montlikadani.tablist.commands.SpongeCommands;
import hu.montlikadani.tablist.config.ConfigHandlers;
import hu.montlikadani.tablist.config.ConfigManager;
import hu.montlikadani.tablist.config.ConfigValues;
import hu.montlikadani.tablist.tablist.TabHandler;
import hu.montlikadani.tablist.tablist.groups.GroupTask;
import hu.montlikadani.tablist.tablist.groups.TabGroup;
import hu.montlikadani.tablist.tablist.objects.ObjectType;
import hu.montlikadani.tablist.tablist.objects.TabListObjects;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Plugin(id = "tablist", name = "TabList", version = "1.0.3", description = "An ultimate animated tablist", authors = "montlikadani", dependencies = @Dependency(id = "spongeapi", version = "7.3.0"))
public class TabList {

	private static TabList instance;

	@Inject
	private PluginContainer pc;

	private ConfigHandlers config, animationsFile, groupsFile;

	private TabHandler tabHandler;
	private Variables variables;
	private GroupTask groupTask;
	private TabListObjects objects;

	private final Set<TabGroup> groupsList = new HashSet<>();
	private final Set<AnimCreator> animations = new HashSet<>();

	public static final Scoreboard BOARD = Sponge.getServer().getServerScoreboard()
			.orElse(Scoreboard.builder().build());

	@Listener
	public void onPluginPreInit(GamePreInitializationEvent e) {
		instance = this;
	}

	@Listener
	public void onPluginInit(GameInitializationEvent ev) {
		initConfigs();
		new SpongeCommands(this);

		tabHandler = new TabHandler(this);
		variables = new Variables();
		objects = new TabListObjects(this);
	}

	@Listener
	public void onServerStarted(GameStartedServerEvent event) {
		Sponge.getEventManager().registerListeners(this, new EventListeners());
		reload();
	}

	@Listener
	public void onPluginStop(GameStoppingEvent e) {
		cancelAll();

		Sponge.getEventManager().unregisterListeners(this);
		Sponge.getCommandManager().getOwnedBy(this).forEach(Sponge.getCommandManager()::removeMapping);
		Sponge.getScheduler().getScheduledTasks(this).forEach(Task::cancel);

		instance = null;
	}

	@Listener
	public void onReload(GameReloadEvent event) {
		reload();
	}

	private void initConfigs() {
		if (config == null) {
			config = new ConfigHandlers(this, "spongeConfig.conf", true);
		}

		if (groupsFile == null) {
			groupsFile = new ConfigHandlers(this, "groups.conf", false);
		}

		if (animationsFile == null) {
			animationsFile = new ConfigHandlers(this, "animations.conf", false);
		}

		config.reload();
		groupsFile.reload();
		animationsFile.reload();
		ConfigValues.loadValues();
	}

	public void reload() {
		tabHandler.removeAll();

		if (groupTask != null) {
			groupTask.cancel();
		}

		Sponge.getScheduler().getScheduledTasks(this).forEach(Task::cancel);

		initConfigs();
		loadAnimations();
		loadGroups();
		variables.loadExpressions();
		updateAll();
	}

	private void loadGroups() {
		groupsList.clear();

		if (!ConfigValues.isTablistGroups()) {
			return;
		}

		int last = 0;
		for (Object gr : groupsFile.get().get("groups").getChildrenMap().keySet()) {
			String name = (String) gr;

			if (name.equalsIgnoreCase("exampleGroup")) {
				continue;
			}

			String prefix = groupsFile.get().getString("", "groups", name, "prefix"),
					suffix = groupsFile.get().getString("", "groups", name, "suffix"),
					permission = groupsFile.get().getString("tablist." + name, "groups", name, "permission");

			int priority = groupsFile.get().getInt(last + 1, "groups", name, "priority");

			groupsList.add(new TabGroup(name, prefix, suffix, permission, priority));

			last = priority;
		}
	}

	private void loadAnimations() {
		animations.clear();

		ConfigManager c = animationsFile.get();
		if (!c.contains("animations")) {
			return;
		}

		for (Object o : c.get("animations").getChildrenMap().keySet()) {
			String name = (String) o;
			List<String> texts = c.getStringList("animations", name, "texts");
			if (texts.isEmpty()) {
				continue;
			}

			boolean random = c.getBoolean(false, "animations", name, "random");
			int time = c.getInt(200, "animations", name, "interval");
			if (time < 0) {
				animations.add(new AnimCreator(name, new ArrayList<>(texts), random));
			} else {
				animations.add(new AnimCreator(name, new ArrayList<>(texts), time, random));
			}
		}
	}

	public String makeAnim(String name) {
		if (name == null) {
			return "";
		}

		while (!animations.isEmpty() && name.contains("%anim:")) { // when using multiple animations
			for (AnimCreator ac : animations) {
				name = name.replace("%anim:" + ac.getAnimName() + "%",
						ac.getTime() > 0 ? ac.getRandomText() : ac.getFirstText());
			}
		}

		return name;
	}

	public void updateAll() {
		Sponge.getServer().getOnlinePlayers().forEach(this::updateAll);
	}

	public void updateAll(final Player player) {
		tabHandler.addPlayer(player);

		if (groupTask != null) {
			groupTask.removePlayer(player);
		} else {
			groupTask = new GroupTask();
		}

		groupTask.addPlayer(player);
		groupTask.runTask();

		for (ObjectType t : ObjectType.values()) {
			if (t != ObjectType.HEARTH) {
				objects.unregisterObjective(player, t.getName());
			}
		}

		if (objects.isCancelled()) {
			objects.loadObjects();
		}
	}

	public void onQuit(Player player) {
		tabHandler.removePlayer(player);

		if (groupTask != null) {
			groupTask.removePlayer(player);
		}

		objects.unregisterAllObjective(player);
	}

	public void cancelAll() {
		tabHandler.removeAll();

		objects.cancelTask();
		objects.unregisterAllObjective();

		if (groupTask != null) {
			groupTask.cancel();
			Sponge.getServer().getOnlinePlayers().forEach(groupTask::removePlayer);
		}

		groupsList.clear();
	}

	public Set<AnimCreator> getAnimations() {
		return animations;
	}

	public Set<TabGroup> getGroupsList() {
		return groupsList;
	}

	public GroupTask getGroupTask() {
		return groupTask;
	}

	public ConfigHandlers getConfig() {
		return config;
	}

	public ConfigHandlers getGroups() {
		return groupsFile;
	}

	public ConfigHandlers getAnimationsFile() {
		return animationsFile;
	}

	public TabHandler getTabHandler() {
		return tabHandler;
	}

	public Variables getVariables() {
		return variables;
	}

	public TabListObjects getTabListObjects() {
		return objects;
	}

	public PluginContainer getPluginContainer() {
		return pc;
	}

	public static TabList get() {
		return instance;
	}
}
