"""Same-host frame deadline. Wall clock only bounds handoff; elapsed work uses monotonic time."""
import time

class FrameDeadline:
    REPLY_RESERVE_SECONDS=.12
    def __init__(self,timeout_ms,deadline_epoch_ms,*,monotonic=time.monotonic,wall=time.time):
        if type(timeout_ms) is not int or not 1<=timeout_ms<=60000 or type(deadline_epoch_ms) is not int:
            raise ValueError('DEADLINE_INVALID')
        self.clock=monotonic
        self.end=monotonic()+min(timeout_ms/1000,deadline_epoch_ms/1000-wall())
    def sender_budget(self):
        left=self.end-self.clock()-self.REPLY_RESERVE_SECONDS
        if left<=0:raise TimeoutError('DEADLINE_BEFORE_DISPATCH')
        return left
    def expired(self):return self.clock()>=self.end
