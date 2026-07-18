# 独立应用资产加密：apps/*.html → assets/enc/*.bin（AES-GCM），构建树内删明文。
# 密钥派生与 AppActivity.assetKey() 完全同源（Long.toHexString = 小写无前导零 hex）。
import hashlib, os
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
a=0x51E0C7A39B24D68; b=0x7D3A9F1C64E8B05; c=0x2C8B5E7F19A3D46
seed="AnKey.Asset.v1|"+format(a^b,'x')+format(b^c,'x')
key=hashlib.sha256(seed.encode()).digest()
base='WubiIME/app/src/main/assets'
os.makedirs(base+'/enc', exist_ok=True)
for name in ('note','pwgen','browser','audio'):
    src=base+'/apps/'+name+'.html'
    pt=open(src,'rb').read()
    iv=os.urandom(12)
    ct=AESGCM(key).encrypt(iv, pt, None)
    open(base+'/enc/'+name+'.bin','wb').write(iv+ct)
    os.remove(src)   # 构建树内删除明文：APK 目录里无 html 可被直接打开
    print(name, 'encrypted', len(pt), '->', len(iv+ct))
