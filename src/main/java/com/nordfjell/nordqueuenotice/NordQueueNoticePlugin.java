package com.nordfjell.nordqueuenotice;

import com.google.inject.Inject;
import com.nordfjell.nordqueue.NordQueuePlugin;
import com.velocitypowered.api.command.*;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.*;
import com.velocitypowered.api.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Plugin(id="nordqueuenotice", name="NordQueueNotice", version="1.1.0",
        description="Bounded session-safe priority queue notices for Nord Fjell",
        authors={"Nord Fjell"}, dependencies={@Dependency(id="nordqueue")})
public final class NordQueueNoticePlugin {
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path config;
    private final Object lifecycle = new Object();
    private final NoticeSchedule<Player> schedule = new NoticeSchedule<>();
    private final DispatchBudget dispatchBudget = new DispatchBudget();
    private final AtomicBoolean ticking = new AtomicBoolean();
    private NordQueuePlugin queuePlugin;
    private NoticeSettings settings;
    private ReloadReader<NoticeSettings> reader;
    private ScheduledTask task;
    private CommandSource reloadRequester;
    // Callable seam for isolated fault tests; not exposed through config or commands.
    private Callable<NoticeSettings> loader;
    private boolean stopping, initialized, reportedFailure;
    private long lastFailureAt, droppedSessions;

    @Inject public NordQueueNoticePlugin(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy=proxy;this.logger=logger;this.config=dataDirectory.resolve("config.properties");
        this.loader=()->NoticeSettings.load(config,false);
    }
    @Subscribe(async=false) public void onProxyInitialize(ProxyInitializeEvent event) {
        synchronized(lifecycle) {
            if(initialized || stopping)return;
            initialized=true;
            registerCommand();
            Optional<?> instance=proxy.getPluginManager().getPlugin("nordqueue").flatMap(container->container.getInstance());
            if(instance.isEmpty() || !(instance.get() instanceof NordQueuePlugin queue)) {
                logger.error("NordQueue API unavailable; notices disabled.");
                return;
            }
            queuePlugin=queue;
            reader=new ReloadReader<>();
            try {settings=NoticeSettings.load(config,true);}
            catch(Exception error) {logger.error("Queue notice config rejected; notices disabled until valid reload: {}",error.getClass().getSimpleName());}
            task=proxy.getScheduler().buildTask(this,this::tick)
                    .delay(NoticeSchedule.TICK_MILLIS,TimeUnit.MILLISECONDS)
                    .repeat(NoticeSchedule.TICK_MILLIS,TimeUnit.MILLISECONDS).schedule();
            logger.info("NordQueueNotice 1.1.0: ready={}, one ticker, scan<=256, send<=32 per 250 ms.",settings!=null);
        }
    }
    private boolean isCurrent(Player player) {
        return player.isActive() && proxy.getPlayer(player.getUniqueId()).orElse(null)==player;
    }
    private Optional<ServerConnection> queueConnection(Player player) {
        return player.getCurrentServer().filter(connection->
                connection.getServerInfo().getName().equalsIgnoreCase(queuePlugin.queueServerName()));
    }
    @Subscribe(async=false) public void onServerPostConnect(ServerPostConnectEvent event) {
        synchronized(lifecycle) {
            if(stopping || queuePlugin==null)return;
            Player player=event.getPlayer();
            if(!isCurrent(player))return;
            Optional<ServerConnection> connection=queueConnection(player);
            if(connection.isPresent()) {
                long delay=settings==null?TimeUnit.MILLISECONDS.toNanos(750):settings.initialDelayNanos();
                if(!schedule.track(player.getUniqueId(),player,connection.get(),System.nanoTime(),delay))
                    droppedSessions=Math.min(Long.MAX_VALUE-1,droppedSessions)+1;
            } else schedule.remove(player.getUniqueId(),player);
            // No delayed per-player task, and no blank-line packet on the main server.
        }
    }
    @Subscribe(async=false) public void onDisconnect(DisconnectEvent event) {
        synchronized(lifecycle) {schedule.remove(event.getPlayer().getUniqueId(),event.getPlayer());}
    }
    private void tick() {
        if(!ticking.compareAndSet(false,true))return;
        try {
            synchronized(lifecycle) {
                if(stopping || queuePlugin==null)return;
                reader.drain(this::publishReload);
                NoticeSettings active=settings;
                if(active==null)return;
                int budget=Math.min(NoticeSchedule.SCAN_BUDGET,schedule.size()), sent=0;
                for(int inspected=0;inspected<budget && sent<NoticeSchedule.SEND_BUDGET;inspected++) {
                    if(!dispatchBudget.available(System.nanoTime()))break;
                    NoticeSchedule.Entry<Player> entry=schedule.next();
                    if(entry==null)break;
                    Player player=entry.owner;
                    Optional<ServerConnection> connection=player.getCurrentServer();
                    if(!isCurrent(player) || connection.orElse(null)!=entry.connection
                            || !connection.get().getServerInfo().getName().equalsIgnoreCase(queuePlugin.queueServerName())) {
                        schedule.remove(entry.id,player);continue;
                    }
                    // Membership can briefly lag ServerPostConnect; keep the tracked
                    // limbo session but do not send to unqueued/suspended players.
                    if(!queuePlugin.isQueued(entry.id))continue;
                    long now=System.nanoTime();
                    if(!schedule.due(entry,now) || !dispatchBudget.take(now)
                            || !schedule.claim(entry,now,active.intervalNanos()))continue;
                    sent++;
                    try {
                        // Recheck directly before enqueueing the cached frame.
                        if(isCurrent(player) && player.getCurrentServer().orElse(null)==entry.connection
                                && queuePlugin.isQueued(entry.id)) player.sendMessage(active.frame());
                    } catch(RuntimeException deliveryFailure) {reportFailure(deliveryFailure);}
                }
            }
        } catch(RuntimeException failure) {
            synchronized(lifecycle) {if(!stopping)reportFailure(failure);}
        } finally {ticking.set(false);}
    }
    private void reportFailure(RuntimeException failure) {
        long now=System.nanoTime();
        if(!reportedFailure || now-lastFailureAt>=TimeUnit.SECONDS.toNanos(30)) {
            reportedFailure=true;lastFailureAt=now;
            logger.warn("Queue notice work failed; retries stay interval-bounded: {}",failure.getClass().getSimpleName());
        }
    }
    private void publishReload(ReloadReader.Outcome<NoticeSettings> outcome) {
        CommandSource requester=reloadRequester;reloadRequester=null;
        String message;
        if(outcome.value()!=null) {
            settings=outcome.value();schedule.reset(System.nanoTime(),settings.initialDelayNanos());
            message="NordQueueNotice configuration reloaded.";
            logger.info("Queue notice config published; interval={} seconds, frame={} JSON bytes.",settings.intervalSeconds(),settings.serializedBytes());
        } else {
            message="NordQueueNotice reload rejected; previous configuration retained.";
            logger.warn("Queue notice reload rejected: {}",outcome.error());
        }
        if(requester!=null && (!(requester instanceof Player player) || isCurrent(player)))
            requester.sendMessage(Component.text(message));
    }
    private void registerCommand() {
        proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder("nordqueuenotice").plugin(this).build(),new ReloadCommand());
    }
    private final class ReloadCommand implements SimpleCommand {
        @Override public boolean hasPermission(Invocation invocation) {
            return invocation.source().hasPermission("nordqueuenotice.admin");
        }
        @Override public void execute(Invocation invocation) {
            // Metadata checking alone is not sufficient for trusted direct executor calls.
            if(!hasPermission(invocation)) {
                invocation.source().sendMessage(Component.text("You do not have permission."));return;
            }
            String[] args=invocation.arguments();
            synchronized(lifecycle) {
                if(args.length==1 && args[0].equalsIgnoreCase("health")) {
                    invocation.source().sendMessage(Component.text("NordQueueNotice: "+(settings!=null && !stopping?"ready":"blocked")
                            +", tracked="+schedule.size()+", reload pending="+(reader!=null&&reader.busy())
                            +", dropped="+droppedSessions+", ticker="+(task!=null&&!stopping)));return;
                }
                if(args.length>1 || (args.length==1&&!args[0].equalsIgnoreCase("reload"))) {
                    invocation.source().sendMessage(Component.text("Usage: /nordqueuenotice [reload|health]"));return;
                }
                if(stopping || reader==null) {
                    invocation.source().sendMessage(Component.text("Queue notice service unavailable."));return;
                }
                if(!reader.start(loader)) {
                    invocation.source().sendMessage(Component.text("Reload already pending."));return;
                }
                reloadRequester=invocation.source();
                invocation.source().sendMessage(Component.text("NordQueueNotice reload queued."));
            }
        }
    }
    @Subscribe(async=false) public void onShutdown(ProxyShutdownEvent event) {
        ReloadReader<NoticeSettings> readerToClose;
        synchronized(lifecycle) {
            if(stopping)return;
            stopping=true;
            if(task!=null)task.cancel();
            schedule.close();reloadRequester=null;readerToClose=reader;
        }
        // Do not await the reader while holding the lifecycle lock.
        if(readerToClose!=null)readerToClose.close();
    }
}
