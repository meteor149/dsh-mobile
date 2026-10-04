"""Atomically publish an already-written file without replacing an existing entry."""
import ctypes
import errno
import json
import os
import sys


def publish(source, destination):
    libc = ctypes.CDLL(None, use_errno=True)
    rename = libc.renameat2
    rename.argtypes = [ctypes.c_int, ctypes.c_char_p, ctypes.c_int, ctypes.c_char_p, ctypes.c_uint]
    rename.restype = ctypes.c_int
    # AT_FDCWD, RENAME_NOREPLACE: paths are passed literally, without a shell.
    if rename(-100, os.fsencode(source), -100, os.fsencode(destination), 1) != 0:
        code = ctypes.get_errno()
        raise OSError(code, os.strerror(code))


if __name__ == "__main__":
    try:
        publish(sys.argv[1], sys.argv[2])
    except (OSError, AttributeError) as error:
        code = getattr(error, "errno", None) or errno.ENOSYS
        sys.stderr.write(json.dumps({"code": errno.errorcode.get(code, "EIO"), "message": str(error)}))
        sys.exit(1)
