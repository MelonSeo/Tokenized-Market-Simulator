"""Offline-tested web demo scope around the existing durable EvaluationGuard.

No HTTP, credentials, approval writer, ledger creator or live CLI. A future
explicitly approved adapter must supply the lineage-bound guard and sender.
This module is NOT installed into production web requests by merely importing it.
"""
import os
from pathlib import Path
from ai_evaluation_guard import GuardError, _bound


class WebDemoScope:
    INPUT = 32768
    OUTPUT = 1600
    EMBEDDING = 1032
    COST = 101140640  # nano-USD, whole new web scope, never an additional global budget

    def __init__(self, folder, guard):
        if Path(folder).is_symlink():
            raise GuardError('WEB_SCOPE_IDENTITY_INVALID')
        self.folder = Path(folder).resolve(strict=True)
        if self.folder.is_symlink() or guard.path.parent.resolve() != self.folder:
            raise GuardError('WEB_SCOPE_IDENTITY_INVALID')
        if not guard.carry or guard.validate_lineage is None:
            raise GuardError('WEB_LINEAGE_REQUIRED')
        self.guard = guard

    def _claim(self, name):
        try:
            with os.fdopen(os.open(self.folder / name, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'wb') as stream:
                stream.write(b'claimed\n');stream.flush();os.fsync(stream.fileno())
        except OSError:
            raise GuardError('WEB_SCOPE_ALREADY_CLAIMED') from None

    def claim_submission(self):
        # One backend-owned context/position/question only; no browser-generated ledger/reset endpoint.
        if not self.guard.approved or (self.folder / 'STOP').exists():
            raise GuardError('EXECUTION_NOT_APPROVED')
        self._claim('web-submission.claim')

    def dispatch(self, path, raw, sender, *, request_id):
        if not self.guard.approved or (self.folder / 'STOP').exists():
            raise GuardError('EXECUTION_NOT_APPROVED')
        if not (self.folder / 'web-submission.claim').is_file():
            raise GuardError('WEB_SUBMISSION_REQUIRED')
        try:
            kind, inp, out, emb = _bound(path, raw, self.guard.policy)
            if inp > self.INPUT or out > self.OUTPUT or emb > self.EMBEDDING:
                raise GuardError('WEB_TOKEN_LIMIT')
            # A rejected request also stops this scope; no alternate endpoint can retry it.
            self._claim('web-' + kind + '.claim')
            return self.guard.dispatch(path, raw, sender, request_id=request_id)
        except Exception:
            try:
                self._claim('STOP')
            finally:
                self.guard.stop()
            raise
