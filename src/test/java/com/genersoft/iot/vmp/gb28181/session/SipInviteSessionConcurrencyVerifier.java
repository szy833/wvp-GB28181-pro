package com.genersoft.iot.vmp.gb28181.session;

// Standalone regression harness: production Redis APIs with per-thread WATCH/MULTI/EXEC semantics.
import com.genersoft.iot.vmp.common.VideoManagerConstants;
import com.genersoft.iot.vmp.conf.UserSetting;
import com.genersoft.iot.vmp.gb28181.bean.SsrcTransaction;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

public class SipInviteSessionConcurrencyVerifier {
    static final String SERVER="verify";
    static final String EXPIRE=VideoManagerConstants.SIP_INVITE_SESSION_V2_EXPIRE+SERVER;
    static final String PENDING=VideoManagerConstants.SIP_INVITE_SESSION_V2_PENDING_DELETE+SERVER;
    static String data(String id){return VideoManagerConstants.SIP_INVITE_SESSION_V2_DATA+SERVER+":"+id;}
    static String stream(String id){return VideoManagerConstants.SIP_INVITE_SESSION_V2_STREAM+SERVER+":rtp"+id;}
    static String meta(String id){return VideoManagerConstants.SIP_INVITE_SESSION_V2_META+SERVER+":"+id;}
    static void check(boolean ok,String text){if(!ok)throw new AssertionError(text);}
    static class Tx {
        Map<String,Long> watches=new HashMap<>(); List<Runnable> commands=new ArrayList<>(); boolean multi;
    }
    @SuppressWarnings({"unchecked","rawtypes"})
    static class Fixture extends RedisTemplate<String,Object> {
        final Map<String,Object> values=new ConcurrentHashMap<>();
        final Map<String,Map<Object,Double>> zsets=new ConcurrentHashMap<>();
        final Map<String,Set<Object>> sets=new ConcurrentHashMap<>();
        final Map<String,Long> versions=new ConcurrentHashMap<>();
        final ThreadLocal<Tx> tx=ThreadLocal.withInitial(Tx::new);
        final Set<String> watched=ConcurrentHashMap.newKeySet();
        final SipInviteSessionManager manager=new SipInviteSessionManager();
        int execs,unwatches,discards; boolean forceConflict,emptyOnce,writeExpiry,throwOnDelete,failEnqueue,failRead;
        Runnable afterScore, beforeExec; CyclicBarrier barrier; int lastBatchLimit; long execDelayMillis;
        final java.util.concurrent.atomic.AtomicInteger barrierVisits=new java.util.concurrent.atomic.AtomicInteger();
        RedisOperations operations;
        ValueOperations valueOps;
        ZSetOperations zsetOps;
        SetOperations setOps;
        RedisConnection connection;
        Fixture() throws Exception {
            UserSetting settings=new UserSetting();settings.setServerId(SERVER);settings.setSipInviteSessionTtlSeconds(3600);
            Field f=SipInviteSessionManager.class.getDeclaredField("userSetting");f.setAccessible(true);f.set(manager,settings);
            f=SipInviteSessionManager.class.getDeclaredField("redisTemplate");f.setAccessible(true);f.set(manager,this);
            valueOps=proxy(ValueOperations.class,(p,m,a)-> switch(m.getName()) {
                case "get" -> {if(failRead)throw new IllegalStateException("read unavailable");yield values.get(a[0]);}
                case "set" -> command((String)a[0],()->values.put((String)a[0],a[1]),null);
                default -> null;
            });
            zsetOps=proxy(ZSetOperations.class,(p,m,a)-> {
                String key=(String)a[0];
                switch(m.getName()) {
                    case "add":
                        if(failEnqueue&&key.equals(PENDING))throw new IllegalStateException("enqueue offline");
                        return command(key,()->zsets.computeIfAbsent(key,k->new ConcurrentHashMap<>()).put(a[1],((Number)a[2]).doubleValue()),true);
                    case "remove":return command(key,()-> {Map<Object,Double> z=zsets.get(key);if(z!=null)for(Object id:(Object[])a[1])z.remove(id);},1L);
                    case "score":
                        Double score=zsets.getOrDefault(key,Map.of()).get(a[1]);
                        if(afterScore!=null){Runnable hook=afterScore;afterScore=null;hook.run();}return score;
                    case "rangeByScore":
                        LinkedHashSet<Object> result=new LinkedHashSet<>();
                        long limit=a.length>3?((Number)a[4]).longValue():Long.MAX_VALUE;
                        lastBatchLimit=(int)limit;
                        zsets.getOrDefault(key,Map.of()).entrySet().stream().sorted(Map.Entry.comparingByValue())
                            .filter(e->e.getValue()>=((Number)a[1]).doubleValue()&&e.getValue()<=((Number)a[2]).doubleValue())
                            .limit(limit).forEach(e->result.add(e.getKey()));return result;
                    default:return null;
                }
            });
            setOps=proxy(SetOperations.class,(p,m,a)-> {
                String key=(String)a[0];
                if(m.getName().equals("remove"))return command(key,()-> {Set<Object>s=sets.get(key);if(s!=null)s.removeAll(Arrays.asList((Object[])a[1]));},1L);
                if(m.getName().equals("add"))return command(key,()->sets.computeIfAbsent(key,k->ConcurrentHashMap.newKeySet()).addAll(Arrays.asList((Object[])a[1])),1L);
                return null;
            });
            HashOperations hash=proxy(HashOperations.class,(p,m,a)->null);
            operations=proxy(RedisOperations.class,(p,m,a)-> {
                Tx t=tx.get();
                switch(m.getName()) {
                    case "opsForValue":return valueOps;
                    case "opsForZSet":return zsetOps;
                    case "opsForSet":return setOps;
                    case "opsForHash":return hash;
                    case "watch":
                        Collection<String> keys=a[0] instanceof Collection?(Collection<String>)a[0]:List.of((String)a[0]);
                        for(String key:keys){t.watches.put(key,versions.getOrDefault(key,0L));watched.add(key);}return null;
                    case "multi":t.multi=true;return null;
                    case "exec":
                        if(execDelayMillis>0)Thread.sleep(execDelayMillis);
                        if(beforeExec!=null){Runnable hook=beforeExec;beforeExec=null;hook.run();}
                        if(barrier!=null&&barrierVisits.getAndIncrement()<2){CyclicBarrier b=barrier;b.await(3,TimeUnit.SECONDS);}
                        synchronized(Fixture.this){
                            execs++;
                            if(writeExpiry)versions.merge(EXPIRE,1L,Long::sum);
                            boolean conflict=forceConflict||t.watches.entrySet().stream().anyMatch(e->!e.getValue().equals(versions.getOrDefault(e.getKey(),0L)));
                            if(emptyOnce){emptyOnce=false;t.commands.clear();t.watches.clear();t.multi=false;return List.of();}
                            List<Object> result=new ArrayList<>();
                            if(!conflict)for(Runnable command:t.commands){command.run();result.add(1L);}
                            t.commands.clear();t.watches.clear();t.multi=false;return conflict?null:result;
                        }
                    case "delete":
                        if(throwOnDelete)throw new IllegalStateException("command failure");
                        return command((String)a[0],()->values.remove(a[0]),true);
                    case "unwatch":t.watches.clear();unwatches++;return null;
                    case "discard":t.multi=false;t.commands.clear();t.watches.clear();discards++;return null;
                    default:throw new UnsupportedOperationException(m.getName());
                }
            });
            connection=proxy(RedisConnection.class,(p,m,a)-> {
                Tx t=tx.get();
                if(m.getName().equals("isQueueing"))return t.multi;
                if(m.getName().equals("discard")){discards++;t.multi=false;t.commands.clear();t.watches.clear();return null;}
                if(m.getName().equals("unwatch")){unwatches++;t.watches.clear();return null;}
                return null;
            });
        }
        static <T>T proxy(Class<T> type,InvocationHandler handler){return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class[]{type},handler);}
        Object command(String key,Runnable action,Object result){Runnable operation=()->{action.run();versions.merge(key,1L,Long::sum);};if(tx.get().multi){tx.get().commands.add(operation);return null;}operation.run();return result;}
        @Override public <T>T execute(SessionCallback<T> callback){return (T)callback.execute(operations);}
        @Override public <T>T execute(RedisCallback<T> callback){return callback.doInRedis(connection);}
        @Override public ValueOperations<String,Object> opsForValue(){return valueOps;}
        @Override public ZSetOperations<String,Object> opsForZSet(){return zsetOps;}
        @Override public SetOperations<String,Object> opsForSet(){return setOps;}
        @Override public HashOperations<String,Object,Object> opsForHash(){return proxy(HashOperations.class,(p,m,a)->null);}
        SsrcTransaction add(String id,String flow){
            SsrcTransaction s=new SsrcTransaction();s.setCallId(id);s.setDeviceId("dev-"+id);s.setApp("rtp");s.setStream(flow);s.setSsrc("0200000001");
            s.setCreatedAt(System.currentTimeMillis());s.setExpireAt(System.currentTimeMillis()+3600_000L);
            values.put(data(id),s);values.put(stream(flow),id);values.put(meta(id),Map.of("deviceId",s.getDeviceId(),"app","rtp","stream",flow));
            zsets.computeIfAbsent(EXPIRE,k->new ConcurrentHashMap<>()).put(id,s.getExpireAt().doubleValue());
            return s;
        }
    }
    interface Test {void run()throws Exception;}
    static int failures,total;
    static void test(String name,Test work){total++;try{work.run();System.out.println("PASS "+name);}catch(Throwable e){failures++;System.out.println("FAIL "+name+": "+e);}}
    public static void main(String[] args)throws Exception {
        failures = 0; total = 0;
        test("unrelated expiry writes",()-> {Fixture f=new Fixture();f.add("a","s");f.writeExpiry=true;f.manager.removeByCallId("a");check(!f.values.containsKey(data("a")),"deletion aborted by unrelated expiry write");});
        test("conflict exhaustion visible",()-> {Fixture f=new Fixture();f.add("a","s");f.forceConflict=true;check(!f.manager.removeByCallId("a"),"exhaustion counted as success");check(f.execs==3,"expected three attempts");});
        test("empty EXEC retries",()-> {Fixture f=new Fixture();f.add("a","s");f.emptyOnce=true;f.manager.removeByCallId("a");check(f.execs==2,"empty result counted as success");check(!f.values.containsKey(data("a")),"session retained");});
        test("replacement owner survives",()-> {Fixture f=new Fixture();f.add("a","s");f.values.put(stream("s"),"replacement");f.manager.removeByCallId("a");check("replacement".equals(f.values.get(stream("s"))),"replacement removed");});
        test("early return releases WATCH",()-> {Fixture f=new Fixture();f.add("a","s").setExpireAt(1L);f.values.put(stream("s"),"replacement");f.manager.getSsrcTransactionByCallId("a");check(f.unwatches>0&&f.tx.get().watches.isEmpty(),"WATCH not reset");});
        if(args.length>0&&args[0].equals("extended"))extended();
        System.out.println("RESULT "+(total-failures)+"/"+total+" passed");if(failures>0)throw new AssertionError(failures + " regression cases failed");
    }
    static void extended()throws Exception {
        test("terminal delete avoids renewal",()-> {Fixture f=new Fixture();f.add("a","s").setExpireAt(1L);f.manager.removeByCallId("a");check(f.execs==1,"terminal deletion renewed session");});
        test("lookup failure queues intent",()-> {Fixture f=new Fixture();f.failRead=true;check(!f.manager.removeByCallId("a"),"read failure counted as deleted");check(f.zsets.getOrDefault(PENDING,Map.of()).containsKey("a"),"lookup failure lost deletion intent");});
        test("corrupt Call-ID not counted as deleted",()-> {Fixture f=new Fixture();f.add("a","s").setCallId("other");f.zsets.put(PENDING,new ConcurrentHashMap<>(Map.of("a",0D)));check(f.manager.retryPendingSessionDeletes(10)==0,"corrupt record counted as deleted");check(f.zsets.get(PENDING).get("a")>System.currentTimeMillis(),"corruption retries without backoff");});
        test("exception resets MULTI",()-> {Fixture f=new Fixture();f.add("a","s");f.throwOnDelete=true;check(!f.manager.removeByCallId("a"),"command failure counted as deleted");check(f.discards==3,"MULTI leaked");check(!f.tx.get().multi&&f.tx.get().watches.isEmpty(),"transaction state retained");});
        test("durable compensation converges",()-> {Fixture f=new Fixture();f.add("a","s");f.forceConflict=true;check(!f.manager.removeByCallId("a"),"conflict counted as deleted");check(f.zsets.get(PENDING).containsKey("a"),"missing intent");f.forceConflict=false;f.zsets.get(PENDING).put("a",0D);check(f.manager.retryPendingSessionDeletes(10)==1,"not drained");check(!f.values.containsKey(data("a"))&&!f.zsets.get(PENDING).containsKey("a"),"delete/intent not atomic");});
        test("compensation protects replacement",()-> {Fixture f=new Fixture();f.add("a","s");f.values.put(stream("s"),"new-owner");f.zsets.put(PENDING,new ConcurrentHashMap<>(Map.of("a",0D)));check(f.manager.retryPendingSessionDeletes(10)==1,"compensation failed");check("new-owner".equals(f.values.get(stream("s"))),"new owner deleted");});
        test("missing DATA uses META cleanup",()-> {Fixture f=new Fixture();f.add("a","s");f.values.remove(data("a"));f.manager.removeByCallId("a");check(!f.values.containsKey(meta("a"))&&!f.values.containsKey(stream("s")),"META orphan retained");});
        test("missing session clears pending intent",()-> {Fixture f=new Fixture();f.zsets.put(PENDING,new ConcurrentHashMap<>(Map.of("gone",0D)));check(f.manager.retryPendingSessionDeletes(10)==1,"missing intent not drained");check(f.zsets.get(PENDING).isEmpty(),"pending retained");});
        test("failed compensation backs off",()-> {Fixture f=new Fixture();f.add("a","s");f.zsets.put(PENDING,new ConcurrentHashMap<>(Map.of("a",0D)));f.forceConflict=true;check(f.manager.retryPendingSessionDeletes(10)==0,"failure counted as deleted");check(f.execs==3,"unbounded retries");check(f.zsets.get(PENDING).get("a")>System.currentTimeMillis(),"no backoff");});
        test("enqueue outage is reported",()-> {Fixture f=new Fixture();f.add("a","s");f.forceConflict=true;f.failEnqueue=true;check(!f.manager.removeByCallId("a"),"enqueue failure counted as success");check(!f.zsets.containsKey(PENDING),"unexpected persisted intent");});
        test("expiry protects renewed DATA",()-> {Fixture f=new Fixture();f.add("a","s");f.zsets.get(EXPIRE).put("a",0D);check(f.manager.cleanupExpiredSessions(10)==0,"renewed session removed");check(f.values.containsKey(data("a")),"DATA missing");});
        test("renewal racing expiry cleanup survives",()-> {Fixture f=new Fixture();f.add("a","s").setExpireAt(1L);f.zsets.get(EXPIRE).put("a",0D);f.beforeExec=()->{f.add("a","s");f.versions.merge(data("a"),1L,Long::sum);};check(f.manager.cleanupExpiredSessions(10)==0,"renewal counted as expired deletion");check(f.values.containsKey(data("a")),"renewed DATA deleted");check(f.tx.get().watches.isEmpty(),"WATCH leaked after retry skip");});
        test("expiry independent of unrelated writes",()-> {Fixture f=new Fixture();f.add("a","s").setExpireAt(1L);f.zsets.get(EXPIRE).put("a",0D);f.writeExpiry=true;check(f.manager.cleanupExpiredSessions(10)==1,"expiry conflicts across members");check(!f.watched.contains(EXPIRE),"global WATCH remains");});
        test("renewal independent of unrelated writes",()-> {Fixture f=new Fixture();f.add("a","s").setExpireAt(1L);f.writeExpiry=true;SsrcTransaction s=f.manager.getSsrcTransactionByCallId("a");check(s.getExpireAt()>System.currentTimeMillis(),"not renewed");check(!f.watched.contains(EXPIRE),"global WATCH remains");});
        test("pending batches capped",()-> {Fixture f=new Fixture();f.manager.retryPendingSessionDeletes(Integer.MAX_VALUE);check(f.lastBatchLimit==100,"batch unbounded");});
        test("pending time budget limits next operation",()-> {Fixture f=new Fixture();f.add("a","s");f.add("b","t");f.zsets.put(PENDING,new ConcurrentHashMap<>(Map.of("a",0D,"b",1D)));f.execDelayMillis=550;check(f.manager.retryPendingSessionDeletes(10)==1,"batch continued beyond time budget");check(f.execs==1&&f.zsets.get(PENDING).size()==1,"remaining intent not preserved");});
        test("different Call-IDs delete concurrently",()-> {Fixture f=new Fixture();f.add("a","s");f.add("b","t");f.barrier=new CyclicBarrier(2);ExecutorService pool=Executors.newFixedThreadPool(2);try{Future<?>a=pool.submit(()->f.manager.removeByCallId("a"));Future<?>b=pool.submit(()->f.manager.removeByCallId("b"));a.get(5,TimeUnit.SECONDS);b.get(5,TimeUnit.SECONDS);check(!f.values.containsKey(data("a"))&&!f.values.containsKey(data("b")),"concurrent session retained");check(f.execs==2,"unrelated deletes conflicted");}finally{pool.shutdownNow();}});
        test("duplicate deletion is idempotent",()-> {Fixture f=new Fixture();f.add("a","s");f.barrier=new CyclicBarrier(2);ExecutorService pool=Executors.newFixedThreadPool(2);try{Future<?>a=pool.submit(()->f.manager.removeByCallId("a"));Future<?>b=pool.submit(()->f.manager.removeByCallId("a"));a.get(5,TimeUnit.SECONDS);b.get(5,TimeUnit.SECONDS);check(!f.values.containsKey(data("a")),"duplicate failed");check(f.zsets.get(PENDING)==null||f.zsets.get(PENDING).isEmpty(),"duplicate retained compensation");}finally{pool.shutdownNow();}});
    }
}
