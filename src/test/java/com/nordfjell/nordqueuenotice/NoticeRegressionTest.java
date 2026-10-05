package com.nordfjell.nordqueuenotice;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import net.kyori.adventure.text.Component;

public final class NoticeRegressionTest {
    interface Test {void run() throws Exception;}
    private static int passed;
    private static void check(String name,Test test) throws Exception {test.run();passed++;System.out.println("PASS: "+name);}
    private static void rejected(Test test) throws Exception {
        try {test.run();}catch(java.io.IOException|IllegalArgumentException|net.kyori.adventure.text.minimessage.ParsingException expected){return;}
        throw new AssertionError("Expected rejection");
    }
    private static Properties properties(String... pairs) {
        Properties properties=new Properties();for(int i=0;i<pairs.length;i+=2)properties.setProperty(pairs[i],pairs[i+1]);return properties;
    }
    private static boolean website(Component root) {
        Deque<Component> nodes=new ArrayDeque<>();nodes.add(root);
        while(!nodes.isEmpty()) {
            Component node=nodes.removeFirst();
            if(node.clickEvent()!=null && node.clickEvent().action()==net.kyori.adventure.text.event.ClickEvent.Action.OPEN_URL
                    && node.clickEvent().payload() instanceof net.kyori.adventure.text.event.ClickEvent.Payload.Text text
                    && text.value().equals("https://example.com/"))return true;
            nodes.addAll(node.children());
        }return false;
    }
    public static void main(String[] args) throws Exception {
        if(!NoticeRegressionTest.class.desiredAssertionStatus())throw new IllegalStateException("Run with -ea");
        Path temporary=Files.createTempDirectory("nordqueuenotice-local-regression-");
        check("Generic defaults and configured example website click retained in cached frame",()->{
            var config=NoticeSettings.from(new Properties());assert config.intervalSeconds()==8;assert config.initialDelayMillis()==750;
            assert config.clearLines()==40;assert !website(config.frame());assert config.serializedBytes()<65536;
            assert config.frame()==config.frame();
            var custom=NoticeSettings.from(properties("message","<click:open_url:'https://example.com/'>Example website</click>"));
            assert website(custom.frame());
        });
        check("Strict numeric bounds reject overflow and invalid values",()->{
            for(String value:List.of("-1","2","3601","9223372036854775807","invalid"))rejected(()->NoticeSettings.from(properties("interval-seconds",value)));
            for(String value:List.of("-1","60001","9223372036854775807"))rejected(()->NoticeSettings.from(properties("initial-delay-millis",value)));
            for(String value:List.of("-1","201","2147483647"))rejected(()->NoticeSettings.from(properties("clear-lines",value)));
        });
        check("Valid boundary values and disabled chat clearing accepted",()->{
            var c=NoticeSettings.from(properties("interval-seconds","3","initial-delay-millis","60000","clear-lines","0"));
            assert c.intervalNanos()==3_000_000_000L;assert c.initialDelayNanos()==60_000_000_000L;
            assert NoticeSettings.from(properties("interval-seconds","3600","clear-lines","200")).clearLines()==200;
        });
        check("Unknown and non-string fields rejected",()->{
            rejected(()->NoticeSettings.from(properties("typo","8")));
            Properties invalid=new Properties();invalid.put("interval-seconds",8);rejected(()->NoticeSettings.from(invalid));
        });
        check("Blank, oversized, control and malformed Unicode message rejected",()->{
            for(String message:List.of(""," ","a".repeat(8193),"bad\u0000text","\uD800","\uDC00"))rejected(()->NoticeSettings.from(properties("message",message)));
        });
        check("Unclosed MiniMessage and excessive tags fail validation",()->{
            rejected(()->NoticeSettings.from(properties("message","<green>unclosed")));
            rejected(()->NoticeSettings.from(properties("message","<newline>".repeat(129))));
        });
        check("Component breadth and depth bounds reject oversized synthetic trees",()->{
            var method=NoticeSettings.class.getDeclaredMethod("validateTree",Component.class);method.setAccessible(true);
            Component deep=Component.text("bottom");for(int i=0;i<40;i++)deep=Component.text("level").append(deep);
            for(Component oversized:List.of(deep,Component.text("root").children(Collections.nCopies(4096,Component.text("child")))))
                rejected(()->{try{method.invoke(null,oversized);}catch(java.lang.reflect.InvocationTargetException error){throw (Exception)error.getCause();}});
        });
        check("Existing valid Properties never rewritten",()->{
            Path path=temporary.resolve("existing.properties");Files.writeString(path,"#existing comment\ninterval-seconds=8\nmessage=NOTICE_LOCAL\n");
            byte[] original=Files.readAllBytes(path);NoticeSettings.load(path,true);NoticeSettings.load(path,false);assert Arrays.equals(original,Files.readAllBytes(path));
        });
        check("Translation argument component nesting cannot bypass tree limit",()->{
            var method=NoticeSettings.class.getDeclaredMethod("validateTree",Component.class);method.setAccessible(true);
            Component nested=Component.text("bottom");for(int i=0;i<40;i++)nested=Component.translatable("synthetic.key").arguments(nested);
            Component root=nested;rejected(()->{try{method.invoke(null,root);}catch(java.lang.reflect.InvocationTargetException error){throw (Exception)error.getCause();}});
        });
        check("Hover entity name and selector separator nesting also bounded",()->{
            var method=NoticeSettings.class.getDeclaredMethod("validateTree",Component.class);method.setAccessible(true);
            Component nested=Component.text("bottom");for(int i=0;i<40;i++)nested=Component.selector("@a").separator(nested);
            Component root=Component.text("entity").hoverEvent(net.kyori.adventure.text.event.HoverEvent.showEntity(net.kyori.adventure.key.Key.key("minecraft:pig"),UUID.randomUUID(),nested));
            rejected(()->{try{method.invoke(null,root);}catch(java.lang.reflect.InvocationTargetException error){throw (Exception)error.getCause();}});
        });
        check("Duplicate decoded Properties keys rejected without rewriting",()->{
            Path path=temporary.resolve("duplicate.properties");Files.writeString(path,"interval-seconds=8\ninterval\\u002dseconds=9\n");
            byte[] original=Files.readAllBytes(path);rejected(()->NoticeSettings.load(path,true));assert Arrays.equals(original,Files.readAllBytes(path));
        });
        check("Legacy ISO-8859-1 and Unicode escape semantics preserved",()->{
            Path path=temporary.resolve("escapes.properties");Files.writeString(path,"message=<green>\\u004eOTICE</green>\n");
            var c=NoticeSettings.load(path,false);assert c.frame()!=null;assert c.intervalSeconds()==8;
        });
        check("Large file, directory and malformed Unicode escape rejected",()->{
            Path large=temporary.resolve("large.properties");Files.writeString(large,"#"+"a".repeat(65536));rejected(()->NoticeSettings.load(large,false));
            rejected(()->NoticeSettings.load(temporary,false));Path malformed=temporary.resolve("malformed.properties");Files.writeString(malformed,"message=\\uXYZZ\n");rejected(()->NoticeSettings.load(malformed,false));
        });
        check("Missing-file reload cannot create config; first initialization creates defaults",()->{
            Path missing=temporary.resolve("create/config.properties");rejected(()->NoticeSettings.load(missing,false));assert !Files.exists(missing);
            var c=NoticeSettings.load(missing,true);assert Files.exists(missing);assert c.intervalSeconds()==8;assert !website(c.frame());
        });
        check("Initial delay is honored even when greater than repeat interval",()->{
            NoticeSchedule<Object> state=new NoticeSchedule<>();Object player=new Object();state.track(UUID.randomUUID(),player,new Object(),0,20_000_000_000L);
            var e=state.next();assert !state.claim(e,3_000_000_000L,3_000_000_000L);assert state.claim(e,20_000_000_000L,3_000_000_000L);
            assert !state.claim(e,20_000_000_001L,3_000_000_000L);
        });
        check("Duplicate post-connect preserves first deadline and one record",()->{
            var state=new NoticeSchedule<Object>();UUID id=UUID.randomUUID();Object owner=new Object(),connection=new Object();
            assert state.track(id,owner,connection,0,750);var e=state.next();
            for(int i=0;i<1000;i++)assert state.track(id,owner,connection,100,750);
            assert state.size()==1;assert state.next()==e;assert state.claim(e,750,8000);
        });
        check("Reconnect replaces identity; stale callback and disconnect cannot affect new session",()->{
            var state=new NoticeSchedule<Object>();UUID id=UUID.randomUUID();Object old=new Object(),fresh=new Object();
            state.track(id,old,new Object(),0,0);var previous=state.next();state.track(id,fresh,new Object(),10,5);
            state.remove(id,old);assert state.size()==1;assert !state.claim(previous,20,10);assert state.claim(state.next(),20,10);
        });
        check("New queue connection invalidates prior deadline token",()->{
            var state=new NoticeSchedule<Object>();UUID id=UUID.randomUUID();Object owner=new Object();
            state.track(id,owner,new Object(),0,0);var old=state.next();state.track(id,owner,new Object(),10,750);
            assert !state.claim(old,1000,8000);assert !state.due(state.next(),759);
        });
        check("Concurrent claims cannot send same session twice",()->{
            var state=new NoticeSchedule<Object>();state.track(UUID.randomUUID(),new Object(),new Object(),0,0);var e=state.next();AtomicInteger claims=new AtomicInteger();
            List<Thread> threads=new ArrayList<>();for(int i=0;i<16;i++){Thread t=new Thread(()->{if(state.claim(e,0,8000))claims.incrementAndGet();});threads.add(t);t.start();}
            for(Thread t:threads)t.join();assert claims.get()==1;
        });
        check("State capacity bounded during churn and full-map replacements",()->{
            var state=new NoticeSchedule<Object>();List<UUID> ids=new ArrayList<>();Object owner=new Object();
            for(int i=0;i<4096;i++){UUID id=UUID.randomUUID();ids.add(id);assert state.track(id,owner,new Object(),0,0);}
            assert !state.track(UUID.randomUUID(),owner,new Object(),0,0);assert state.track(ids.get(0),new Object(),new Object(),0,0);
            for(int i=0;i<10000;i++)state.track(ids.get(1),new Object(),new Object(),0,0);assert state.size()==4096;
            state.remove(ids.get(2),owner);assert state.track(UUID.randomUUID(),owner,new Object(),0,0);assert state.size()==4096;
        });
        check("Reload changes deadlines without new task or stale entry publication",()->{
            var state=new NoticeSchedule<Object>();state.track(UUID.randomUUID(),new Object(),new Object(),0,0);var e=state.next();state.reset(1000,750);
            assert !state.claim(e,1749,8000);assert state.claim(e,1750,8000);assert state.size()==1;
        });
        check("NanoTime wrap keeps deadlines and budget correct",()->{
            long start=Long.MAX_VALUE-100;var state=new NoticeSchedule<Object>();state.track(UUID.randomUUID(),new Object(),new Object(),start,200);var e=state.next();
            assert !state.claim(e,start+199,1000);assert state.claim(e,start+200,1000);
            var budget=new DispatchBudget();for(int i=0;i<32;i++)assert budget.take(start);assert !budget.take(start);assert budget.take(start+DispatchBudget.COST_NANOS);
        });
        check("Dispatch token bucket caps burst, refills and prevents catch-up flood",()->{
            var budget=new DispatchBudget();for(int i=0;i<32;i++)assert budget.take(0);assert !budget.take(0);
            assert !budget.available(DispatchBudget.COST_NANOS-1);assert budget.take(DispatchBudget.COST_NANOS);assert !budget.take(DispatchBudget.COST_NANOS);
            for(int i=0;i<32;i++)assert budget.take(60_000_000_000L);assert !budget.take(60_000_000_000L);
        });
        check("900 simultaneous waiters visited fairly under scan/send/rate bounds",()->{
            var state=new NoticeSchedule<Integer>();for(int i=0;i<900;i++)state.track(new UUID(0,i),i,new Object(),0,0);
            var bucket=new DispatchBudget();Set<Integer> delivered=new HashSet<>();int attempts=0;
            for(long now=0;now<=8_000_000_000L;now+=250_000_000L){int scan=Math.min(256,state.size()),sent=0;
                for(int i=0;i<scan&&sent<32;i++){if(!bucket.available(now))break;var e=state.next();if(state.due(e,now)&&bucket.take(now)&&state.claim(e,now,8_000_000_000L)){delivered.add(e.owner);sent++;attempts++;}}
                assert sent<=32;assert attempts<=32+now/DispatchBudget.COST_NANOS;
            }assert delivered.size()==900;assert state.size()==900;
        });
        check("Closing schedule rejects stale callbacks and future tracking",()->{
            var state=new NoticeSchedule<Object>();state.track(UUID.randomUUID(),new Object(),new Object(),0,0);var e=state.next();state.close();
            assert state.size()==0;assert state.next()==null;assert !state.claim(e,1,10);assert !state.track(UUID.randomUUID(),new Object(),new Object(),0,0);
        });
        check("Reload reader admits one job and publishes one result",()->{
            var reader=new ReloadReader<Integer>();CountDownLatch started=new CountDownLatch(1),finish=new CountDownLatch(1);AtomicInteger published=new AtomicInteger();
            try {assert reader.start(()->{started.countDown();finish.await();return 42;});assert started.await(2,TimeUnit.SECONDS);
                for(int i=0;i<1000;i++)assert !reader.start(()->99);finish.countDown();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
                while(published.get()==0&&System.nanoTime()-deadline<0){reader.drain(result->{assert result.error()==null;assert result.value()==42;published.incrementAndGet();});Thread.sleep(10);}
                assert published.get()==1;assert !reader.busy();reader.drain(result->{throw new AssertionError("Duplicate publication");});
            }finally{finish.countDown();reader.close();}
        });
        check("Invalid reload result is bounded and does not replace consumer settings",()->{
            var reader=new ReloadReader<Integer>();AtomicInteger active=new AtomicInteger(7),outcomes=new AtomicInteger();
            try {assert reader.start(()->{throw new java.io.IOException("synthetic");});long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
                while(outcomes.get()==0&&System.nanoTime()-deadline<0){reader.drain(result->{assert result.value()==null;assert result.error().equals("IOException");if(result.value()!=null)active.set(result.value());outcomes.incrementAndGet();});Thread.sleep(10);}
                assert outcomes.get()==1;assert active.get()==7;
            }finally{reader.close();}
        });
        check("Shutdown interrupts reader and discards late result",()->{
            var reader=new ReloadReader<Integer>();CountDownLatch started=new CountDownLatch(1);AtomicBoolean interrupted=new AtomicBoolean();
            assert reader.start(()->{started.countDown();try{Thread.sleep(10000);}catch(InterruptedException e){interrupted.set(true);throw e;}return 1;});
            assert started.await(2,TimeUnit.SECONDS);reader.close();assert interrupted.get();assert !reader.start(()->2);reader.drain(result->{throw new AssertionError("Late result");});
        });
        System.out.println("UNIT_CHECKS_PASSED="+passed);
        if(args.length==1)Files.writeString(Path.of(args[0]),"{\"passed\":true,\"scenarios\":"+passed+",\"assertionsEnabled\":true}\n");
    }
}
