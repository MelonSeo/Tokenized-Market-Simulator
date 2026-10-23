"""Local operator input only; never ship a working presentation password."""
import os


def local_demo_password():
    value = os.environ.get('TMS_LOCAL_DEMO_PASSWORD', '')
    if not 8 <= len(value.encode('utf-8')) <= 72 or any(c in value for c in ('\r', '\n', '\0')):
        raise ValueError('LOCAL_DEMO_PASSWORD_REQUIRED')
    return value
