import unittest
from ai_deadline import FrameDeadline
class DeadlineTest(unittest.TestCase):
    def test_preparation_and_reply_time_deducted_without_reset(self):
        now=[0.0];d=FrameDeadline(10000,110000,monotonic=lambda:now[0],wall=lambda:100)
        now[0]=.28;self.assertAlmostEqual(d.sender_budget(),9.6)
        now[0]=9.9
        with self.assertRaises(TimeoutError):d.sender_budget()
        now[0]=10;self.assertTrue(d.expired())
    def test_handoff_time_is_not_a_fresh_ten_seconds(self):
        d=FrameDeadline(10000,110000,monotonic=lambda:0,wall=lambda:100.25)
        self.assertAlmostEqual(d.sender_budget(),9.63)
    def test_future_wall_deadline_cannot_increase_declared_budget(self):
        d=FrameDeadline(1000,200000,monotonic=lambda:0,wall=lambda:100)
        self.assertAlmostEqual(d.sender_budget(),.88)
    def test_expired_handoff_never_reaches_sender(self):
        d=FrameDeadline(10000,100000,monotonic=lambda:0,wall=lambda:101)
        with self.assertRaises(TimeoutError):d.sender_budget()
if __name__=='__main__':unittest.main()
