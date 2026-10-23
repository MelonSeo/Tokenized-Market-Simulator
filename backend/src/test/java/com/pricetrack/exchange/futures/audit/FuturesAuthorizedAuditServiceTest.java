package com.pricetrack.exchange.futures.audit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.springframework.web.server.ResponseStatusException;

class FuturesAuthorizedAuditServiceTest {
    FuturesAuthorizedAuditObserver observer=mock(FuturesAuthorizedAuditObserver.class);
    FuturesAuditDeploymentStore store=mock(FuturesAuditDeploymentStore.class);
    FuturesAuditDeployment target=FuturesAuditDeploymentTest.target(FuturesAuditDeployment.Route.B_V2);
    FuturesAuthorizedAuditService service;
    @AfterEach void stop(){if(service!=null)service.close();}
    void create(Duration deadline){service=new FuturesAuthorizedAuditService(observer,store,new FuturesAuditDeployment.Registry(List.of(target)),deadline);}
    @Test void targetsArePublicIdentityAndHistoryIsExplicitlyNotPersisted(){
        create(Duration.ofSeconds(1));assertThat(service.targets().targets()).containsExactly(target);
        assertThat(service.targets().history()).contains("NOT_PERSISTED");verifyNoInteractions(observer,store);
    }
    @Test void registeredBaselineAndObservationUseRequestedActorOnly(){
        create(Duration.ofSeconds(1));var envelope=FuturesAuditDeploymentTest.envelope(target);
        when(observer.baseline(target.key(),7)).thenReturn(envelope);
        assertThat(service.baseline(target.key(),7)).isEqualTo(envelope);service.run(7);verify(observer).observe(7);
        assertThatThrownBy(()->service.baseline("arbitrary-rpc-address",7)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("400");
        assertThatThrownBy(()->service.run(0)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("403");
    }
    @Test void exceptionIsSanitizedAndNoOriginalThrowableEscapes(){
        create(Duration.ofSeconds(1));when(observer.observe(1)).thenThrow(new IllegalStateException("private-signature-raw-secret"));
        assertThatThrownBy(()->service.run(1)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("503").hasMessageNotContaining("private-signature").hasNoCause();
    }
    @Test void concurrentRequestIsRejectedWithoutQueueing()throws Exception {
        create(Duration.ofSeconds(2));var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(observer.observe(1)).thenAnswer(i->{entered.countDown();release.await();return null;});
        try(var callers=Executors.newSingleThreadExecutor()){
            var first=callers.submit(()->service.run(1));assertThat(entered.await(1,TimeUnit.SECONDS)).isTrue();
            try{assertThatThrownBy(()->service.run(2)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("429");verify(observer,never()).observe(2);}
            finally{release.countDown();}first.get(1,TimeUnit.SECONDS);
        }
    }
    @Test void timeoutCancelsAndInterruptIgnoringWorkKeepsAdmissionClosed()throws Exception {
        create(Duration.ofMillis(100));var interrupted=new AtomicBoolean();var release=new CountDownLatch(1);
        when(observer.observe(1)).thenAnswer(i->{while(release.getCount()>0){try{release.await();}catch(InterruptedException stop){interrupted.set(true);}}return null;});
        try{
            assertThatThrownBy(()->service.run(1)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("DEADLINE");
            assertThatThrownBy(()->service.run(2)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("429");
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);while(!interrupted.get()&&System.nanoTime()<until)Thread.onSpinWait();
            assertThat(interrupted).isTrue();verify(observer,never()).observe(2);
        }finally{release.countDown();}
    }
    @Test void closedServiceCannotStartNewObservation(){create(Duration.ofSeconds(1));service.close();assertThatThrownBy(()->service.run(1)).hasMessageContaining("CLOSED");verifyNoInteractions(observer);}
    @Test void timeoutTokenSurvivesClearedInterruptAndPreventsDelayedBaselineRegistration()throws Exception {
        create(Duration.ofMillis(100));var release=new CountDownLatch(1);var finished=new CountDownLatch(1);var fenced=new AtomicBoolean();
        when(observer.baseline(target.key(),1)).thenAnswer(i->{
            while(release.getCount()>0){try{release.await();}catch(InterruptedException ignored){/* Simulate a dependency ignoring interruption. */}}
            try{Thread.interrupted();FuturesAuditCancellation.check();store.register(FuturesAuditDeploymentTest.envelope(target));}
            catch(IllegalStateException cancelled){fenced.set(true);}finally{finished.countDown();}return null;
        });
        try{assertThatThrownBy(()->service.baseline(target.key(),1)).hasMessageContaining("DEADLINE");}
        finally{release.countDown();}
        assertThat(finished.await(1,TimeUnit.SECONDS)).isTrue();assertThat(fenced).isTrue();verify(store,never()).register(any());
    }
}
