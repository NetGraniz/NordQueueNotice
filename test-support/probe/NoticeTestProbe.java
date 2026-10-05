package com.nordfjell.nordqueuenotice.test;

import com.google.inject.Inject;
import com.google.gson.Gson;
import com.nordfjell.nordqueue.NordQueuePlugin;
import com.velocitypowered.api.command.*;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.permission.PermissionsSetupEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.proxy.*;
import com.velocitypowered.api.permission.*;
import com.velocitypowered.api.proxy.*;
import com.velocitypowered.api.scheduler.TaskStatus;
import org.slf4j.Logger;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

/** LOCAL fixture control only. No production registration or data access. */
public final class NoticeTestProbe {
    private final ProxyServer proxy;
    private final Logger logger;
    private final Gson gson=new Gson();
    private final Map<UUID,Map<String,Boolean>> permissions=new ConcurrentHashMap<>();
    private Object target,originalLoader;
    private NordQueuePlugin queue;
    private Player remembered;
    private volatile long delay;
    @Inject public NoticeTestProbe(ProxyServer proxy,Logger logger){this.proxy=proxy;this.logger=logger;}
    @Subscribe public void initialize(ProxyInitializeEvent event){
        target=proxy.getPluginManager().getPlugin("nordqueuenotice").orElseThrow().getInstance().orElseThrow();
        queue=(NordQueuePlugin)proxy.getPluginManager().getPlugin("nordqueue").orElseThrow().getInstance().orElseThrow();
        proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder("qntest").plugin(this).build(),new SimpleCommand(){
            public boolean hasPermission(Invocation i){return !(i.source() instanceof Player);}
            public void execute(Invocation i){if(!(i.source() instanceof Player))control(i.arguments());}
        });
        logger.info("QNPROBE_READY");
    }
    @Subscribe public void permission(PermissionsSetupEvent event){
        if(!(event.getSubject() instanceof Player player))return;
        PermissionFunction original=event.createFunction(player);
        event.setProvider(subject->key->{Boolean value=permissions.getOrDefault(player.getUniqueId(),Map.of()).get(key);return value==null?original.getPermissionValue(key):Tristate.fromBoolean(value);});
    }
    private Object field(Object object,String name)throws Exception {Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);}
    private Object method(Object object,String name)throws Exception {Method method=object.getClass().getDeclaredMethod(name);method.setAccessible(true);return method.invoke(object);}
    private void call(String name,Class<?> type,Object value)throws Exception {Method method=target.getClass().getDeclaredMethod(name,type);method.setAccessible(true);method.invoke(target,value);}
    private void direct(Player player)throws Exception {
        Class<?> type=Arrays.stream(target.getClass().getDeclaredClasses()).filter(c->c.getSimpleName().equals("ReloadCommand")).findFirst().orElseThrow();
        Constructor<?> constructor=type.getDeclaredConstructor(target.getClass());constructor.setAccessible(true);
        SimpleCommand command=(SimpleCommand)constructor.newInstance(target);
        SimpleCommand.Invocation invocation=(SimpleCommand.Invocation)Proxy.newProxyInstance(SimpleCommand.Invocation.class.getClassLoader(),new Class<?>[]{SimpleCommand.Invocation.class},(p,m,args)->switch(m.getName()){
            case "source" -> player;case "arguments" -> new String[]{"reload"};case "alias" -> "nordqueuenotice";default -> null;
        });
        command.execute(invocation);
    }
    private void state(String id)throws Exception {
        Map<String,Object> result=new LinkedHashMap<>();
        Object settings=field(target,"settings");result.put("ready",settings!=null);
        result.put("tasks",proxy.getScheduler().tasksByPlugin(target).stream().filter(t->t.status()==TaskStatus.SCHEDULED).count());
        result.put("workers",Thread.getAllStackTraces().keySet().stream().filter(t->t.isAlive()&&t.getName().equals("NordQueueNotice-config-reader")).count());
        if(settings!=null){result.put("interval",method(settings,"intervalSeconds"));try{result.put("frame",System.identityHashCode(method(settings,"frame")));}catch(NoSuchMethodException old){result.put("frame",-1);}}
        try{result.put("tracked",method(field(target,"schedule"),"size"));}catch(NoSuchFieldException old){result.put("tracked",((Map<?,?>)field(target,"lastSent")).size());}
        result.put("main",proxy.getServer("main").orElseThrow().getPlayersConnected().stream().map(Player::getUsername).sorted().toList());
        result.put("queue",proxy.getServer("queue").orElseThrow().getPlayersConnected().stream().map(Player::getUsername).sorted().toList());
        logger.info("QNSTATE {} {}",id,gson.toJson(result));
    }
    private void control(String[] args){
        String operation=String.join(" ",args);
        try {
            switch(args[0]) {
                case "state" -> state(args[1]);
                case "repeat" -> {Player player=proxy.getPlayer(args[1]).orElseThrow();for(int i=0;i<Integer.parseInt(args[2]);i++)call("onServerPostConnect",ServerPostConnectEvent.class,new ServerPostConnectEvent(player,null));}
                case "direct" -> direct(proxy.getPlayer(args[1]).orElseThrow());
                case "permission" -> {Player player=proxy.getPlayer(args[1]).orElseThrow();permissions.computeIfAbsent(player.getUniqueId(),ignored->new ConcurrentHashMap<>()).put(args[2],Boolean.parseBoolean(args[3]));}
                case "remember" -> remembered=proxy.getPlayer(args[1]).orElseThrow();
                case "stalequit" -> call("onDisconnect",DisconnectEvent.class,new DisconnectEvent(remembered,DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN));
                case "unqueue", "enqueue" -> {Player player=proxy.getPlayer(args[1]).orElseThrow();Method method=queue.getClass().getDeclaredMethod(args[0].equals("unqueue")?"remove":"enqueue",Player.class);method.setAccessible(true);method.invoke(queue,player);}
                case "slow" -> {
                    delay=Long.parseLong(args[1]);Field loader=target.getClass().getDeclaredField("loader");loader.setAccessible(true);if(originalLoader==null)originalLoader=loader.get(target);
                    loader.set(target,Proxy.newProxyInstance(Callable.class.getClassLoader(),new Class<?>[]{Callable.class},(p,m,a)->{long wait=delay;if(wait>0)Thread.sleep(wait);try{return m.invoke(originalLoader,a);}catch(InvocationTargetException e){throw e.getCause();}}));
                }
                case "tick" -> method(target,"tick");
                case "shutdown" -> call("onShutdown",ProxyShutdownEvent.class,new ProxyShutdownEvent());
            }
            logger.info("QNTEST_OK {}",operation);
        }catch(Exception failure){logger.error("QNTEST_FAILED {}",operation,failure);}
    }
}
