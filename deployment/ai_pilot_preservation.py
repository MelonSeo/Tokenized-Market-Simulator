"""Small durable source-preservation manifest; never reads runtime secrets or ledgers."""
import argparse
import hashlib
import json
import subprocess
from pathlib import Path


def git(root, *args):
    return subprocess.check_output(['git', '-C', str(root), *args], text=True, encoding='utf8').strip()


def snapshot(root, workspace):
    def dirty(folder):
        paths = git(folder, 'ls-files', '-m', '-o', '--exclude-standard', '-z').split('\0')
        result = {}
        for name in paths:
            if not name:
                continue
            p = folder / name
            # Only Git-visible source, never ignored evidence or runtime databases.
            if p.is_file() and not p.is_symlink():
                with p.open('rb') as stream:
                    result[name] = hashlib.file_digest(stream, 'sha256').hexdigest()
            else:
                result[name] = 'MISSING_OR_LINK'
        return result
    return {'worktrees': git(root, 'worktree', 'list', '--porcelain'),
            'rootHead': git(root, 'rev-parse', 'HEAD'), 'rootDirty': dirty(root),
            'aiHead': git(workspace, 'rev-parse', 'HEAD'), 'aiDirty': dirty(workspace),
            'staged': git(workspace, 'diff', '--cached', '--name-only')}


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('root', type=Path); p.add_argument('workspace', type=Path)
    p.add_argument('output', type=Path)
    a = p.parse_args()
    with a.output.open('x', encoding='utf8') as out:
        json.dump(snapshot(a.root.resolve(), a.workspace.resolve()), out, ensure_ascii=False)
    print('SOURCE_BASELINE_SAVED')
