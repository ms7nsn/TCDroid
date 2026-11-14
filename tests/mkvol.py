# Générateur / vérificateur INDÉPENDANT de volumes TrueCrypt (AES-XTS, SHA-512) écrit d'après Volumes.c.
import os, struct, zlib, hashlib, sys
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
G = 131072
def xts(k64, unit, data, enc):
    c = Cipher(algorithms.AES(k64), modes.XTS(struct.pack('<QQ', unit, 0)))
    o = c.encryptor() if enc else c.decryptor(); return o.update(data) + o.finalize()
def pool_pw(pw, kfs):
    tab = []
    for i in range(256):
        c = i
        for _ in range(8): c = (c >> 1) ^ 0xEDB88320 if c & 1 else c >> 1
        tab.append(c)
    pool = bytearray(64)
    for kf in kfs:
        crc = 0xffffffff; w = 0
        for byte in kf[:1048576]:
            crc = tab[(crc ^ byte) & 0xff] ^ (crc >> 8)
            for sh in (24, 16, 8, 0):
                pool[w] = (pool[w] + ((crc >> sh) & 0xff)) & 0xff; w += 1
            if w >= 64: w = 0
    n = max(len(pw), 64); out = bytearray(n)
    for i in range(64): out[i] = ((pw[i] if i < len(pw) else 0) + pool[i]) & 0xff
    for i in range(64, len(pw)): out[i] = pw[i]
    return bytes(out)
def header(pw, mk, vol, hid, start):
    h = bytearray(512); salt = os.urandom(64); h[0:64] = salt
    struct.pack_into('>I', h, 64, 0x54525545); struct.pack_into('>H', h, 68, 5); struct.pack_into('>H', h, 70, 0x700)
    struct.pack_into('>Q', h, 92, hid); struct.pack_into('>Q', h, 100, vol); struct.pack_into('>Q', h, 108, start)
    struct.pack_into('>Q', h, 116, hid or vol); struct.pack_into('>I', h, 128, 512)
    h[256:512] = mk
    struct.pack_into('>I', h, 72, zlib.crc32(bytes(h[256:512])))
    struct.pack_into('>I', h, 252, zlib.crc32(bytes(h[64:252])))
    dk = hashlib.pbkdf2_hmac('sha512', pw, salt, 1000, 192)
    h[64:] = xts(dk[:32] + dk[32:64], 0, bytes(h[64:]), True)
    return bytes(h)
def outer_pat(i): return (i * 7 + 3) & 0xff
def hid_pat(i): return (i * 13 + 5) & 0xff
def gen(path):
    kf = os.urandom(3000); open('kf.bin', 'wb').write(kf)
    OUT, HID = 1 << 20, 1 << 18
    host = 2 * G + OUT
    mk1, mk2 = os.urandom(256), os.urandom(256)
    f = bytearray(os.urandom(host))
    f[0:512] = header(pool_pw(b'outerpass', [kf]), mk1, OUT, 0, G)
    f[host - G:host - G + 512] = header(pool_pw(b'outerpass', [kf]), mk1, OUT, 0, G)
    hstart = host - G - HID
    f[65536:65536 + 512] = header(b'hiddenpass', mk2, HID, HID, hstart)
    plain = bytes(outer_pat(i) for i in range(OUT))
    for u in range(OUT // 512):
        f[G + u*512:G + (u+1)*512] = xts(mk1[:32] + mk1[32:64], (G + u*512)//512, plain[u*512:(u+1)*512], True)
    hp = bytes(hid_pat(i) for i in range(HID))
    for u in range(HID // 512):
        f[hstart + u*512:hstart + (u+1)*512] = xts(mk2[:32] + mk2[32:64], (hstart + u*512)//512, hp[u*512:(u+1)*512], True)
    open(path, 'wb').write(f); open('mk1.bin','wb').write(mk1); open('mk2.bin','wb').write(mk2)
    open('geom.txt', 'w').write('%d %d %d\n' % (host, G, hstart))
def verify(path):
    f = open(path, 'rb').read(); host, G_, hstart = map(int, open('geom.txt').read().split())
    mk1 = open('mk1.bin','rb').read(); mk2 = open('mk2.bin','rb').read()
    def dec(start, mk, voff, n):
        a0 = (start + voff) & ~511; a1 = (start + voff + n + 511) & ~511; out = b''
        for u in range(a0, a1, 512): out += xts(mk[:32]+mk[32:64], u//512, f[u:u+512], False)
        return out[(start+voff)-a0:(start+voff)-a0+n]
    ok = dec(G, mk1, 5000, 777) == bytes((i*31+1) & 0xff for i in range(777))
    ok2 = dec(hstart, mk2, 3001, 1500) == bytes((i*17+9) & 0xff for i in range(1500))
    print('python decrypts Java outer write:', 'OK' if ok else 'FAIL'); print('python decrypts Java hidden write:', 'OK' if ok2 else 'FAIL')
if __name__ == '__main__':
    if sys.argv[1] == 'gen': gen(sys.argv[2])
    else: verify(sys.argv[2])
