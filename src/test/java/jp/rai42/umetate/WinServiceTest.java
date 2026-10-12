package jp.rai42.umetate;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.bukkit.command.CommandSender;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class WinServiceTest {
    @Test void retriesOnlyUnsentCountsAndKeepsRequestOrderAndMainCallbacks() throws Exception {
        var calls=new CopyOnWriteArrayList<WinRequest>();var callbacks=new LinkedBlockingQueue<Runnable>();var events=new ArrayList<WinRequestedEvent>();var warnings=new ArrayList<String>();
        Thread caller=Thread.currentThread();
        WinSink sink=r -> {assertNotEquals(caller,Thread.currentThread());assertTrue(Thread.currentThread().isDaemon());calls.add(r);
            return calls.size()==1?new WinSink.Result(false,2,r.count(),"partial"):new WinSink.Result(true,r.count(),r.count(),"");};
        try(var service=new WinService(sink,"fake",3,1000,10,callbacks::add,e->{assertEquals(caller,Thread.currentThread());events.add(e);},warnings::add)) {
            service.win(5,WinService.Reason.FILL_COMPLETE);service.lose(1,WinService.Reason.FRAME_BROKEN);
            for(int i=0;i<2;i++)Objects.requireNonNull(callbacks.poll(3,TimeUnit.SECONDS)).run();
            assertEquals(List.of(new WinRequest("win",5),new WinRequest("win",3),new WinRequest("lose",1)),calls);
            assertEquals(List.of(5,-1),events.stream().map(WinRequestedEvent::getDelta).toList());assertTrue(warnings.isEmpty());
            assertTrue(service.status().contains("Win 5 / Lose 1"));
        }
    }
    @Test void boundedQueueRejectsWithoutPublishingAndStopCancelsActiveSend() throws Exception {
        var entered=new CountDownLatch(1);var interrupted=new CountDownLatch(1);var events=new ArrayList<WinRequestedEvent>();
        WinSink sink=r->{entered.countDown();try{new CountDownLatch(1).await();}catch(InterruptedException e){interrupted.countDown();Thread.currentThread().interrupt();}return new WinSink.Result(false,0,r.count(),"stopped");};
        var service=new WinService(sink,"fake",3,10,1,Runnable::run,events::add,s->{});
        try {service.win(1,WinService.Reason.COMMAND);assertTrue(entered.await(2,TimeUnit.SECONDS));service.lose(1,WinService.Reason.COMMAND);
            assertThrows(IllegalArgumentException.class,()->service.win(1,WinService.Reason.COMMAND));assertEquals(2,events.size());assertEquals(1,service.queued());
        } finally {service.close();}
        assertTrue(interrupted.await(2,TimeUnit.SECONDS));assertThrows(IllegalArgumentException.class,()->service.win(1,WinService.Reason.COMMAND));
    }
    @Test void retryExhaustionNotifiesSenderAndConsoleWithRemainingCount() throws Exception {
        var callback=new LinkedBlockingQueue<Runnable>();var warnings=new ArrayList<String>();var calls=new CopyOnWriteArrayList<Integer>();var sender=mock(CommandSender.class);
        WinSink sink=r->{calls.add(r.count());return new WinSink.Result(false,calls.size()==1?1:0,r.count(),"offline");};
        try(var service=new WinService(sink,"fake",3,10,2,callback::add,e->{},warnings::add)) {
            service.submit(new WinRequest("win",4),WinService.Reason.COMMAND,sender);Objects.requireNonNull(callback.poll(2,TimeUnit.SECONDS)).run();
            assertEquals(List.of(4,3,3,3),calls);assertEquals(1,service.lastResult().sent());assertFalse(service.lastResult().completed());
            assertTrue(warnings.getFirst().contains("未送信 3件"));verify(sender).sendMessage(contains("未送信 3件"));
        }
    }
    @Test void finalConnectionFailureAfterAllSendsNeverReplays() throws Exception {
        var callback=new LinkedBlockingQueue<Runnable>();var calls=new CopyOnWriteArrayList<Integer>();
        try(var service=new WinService(r->{calls.add(r.count());return new WinSink.Result(false,r.count(),r.count(),"closed");},"fake",3,10,2,callback::add,e->{},s->{})){
            service.win(5,WinService.Reason.COMMAND);assertNotNull(callback.poll(2,TimeUnit.SECONDS));assertEquals(List.of(5),calls);
        }
    }
    @Test void noopDoesNotClaimExternalSendsAndDoesNotRetry() throws Exception {
        var callback=new LinkedBlockingQueue<Runnable>();
        try(var service=new WinService(new NoopWinSink(),"none",3,10,2,callback::add,e->{},s->{})) {
            service.win(5,WinService.Reason.COMMAND);assertNotNull(callback.poll(2,TimeUnit.SECONDS));assertTrue(service.lastResult().completed());assertEquals(0,service.lastResult().sent());
            assertTrue(service.status().contains("Win 0 / Lose 0"));
        }
    }
    @Test void commandsValidateCountsAndSynonymsAndCompletion() {
        assertEquals(new WinRequest("win",1),WinRequest.parse(new String[]{"add"},1000));assertEquals(new WinRequest("lose",5),WinRequest.parse(new String[]{"remove","5"},1000));
        for(String[] a:List.of(new String[]{},new String[]{"set","3"},new String[]{"win","0"},new String[]{"lose","-1"},new String[]{"win","1.5"},new String[]{"win","1001"},new String[]{"win","9999999999999999"},new String[]{"win","1","x"}))
            assertThrows(IllegalArgumentException.class,()->WinRequest.parse(a,1000));
        var service=mock(WinService.class);when(service.maximum()).thenReturn(1000);when(service.status()).thenReturn("status");
        var command=new WinCommand(service);var sender=mock(CommandSender.class);when(sender.hasPermission("raiumetate.admin")).thenReturn(true);
        command.onCommand(sender,null,"win",new String[]{"win","5"});verify(service).submit(new WinRequest("win",5),WinService.Reason.COMMAND,sender);
        command.onCommand(sender,null,"win",new String[]{"status"});verify(sender).sendMessage("status");
        assertEquals(List.of("win","lose","status"),command.onTabComplete(sender,null,"win",new String[]{""}));
        assertEquals(List.of("1","5","10"),command.onTabComplete(sender,null,"win",new String[]{"win",""}));
    }
}
