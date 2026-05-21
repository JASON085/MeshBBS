# -*- mode: python ; coding: utf-8 -*-
# PyInstaller spec for Mesh BBS 文字客戶端 (mesh_bbs_client.py)

from PyInstaller.utils.hooks import collect_submodules, collect_data_files

block_cipher = None

EXCLUDES = [
    # 科學計算（不使用）
    'numpy', 'pandas', 'scipy', 'matplotlib', 'sklearn',
    # GUI 框架（純文字模式，全部排除）
    'tkinter', '_tkinter', 'tcl', 'tk',
    'PyQt5', 'PyQt6', 'PySide2', 'PySide6', 'wx', 'gi',
    # 圖像處理
    'PIL', 'Pillow', 'cv2',
    # Jupyter / IPython
    'IPython', 'jupyter', 'notebook', 'ipykernel',
    # HTTP 伺服器（client 不需要）
    'aiohttp', 'xmlrpc',
    # 不需要的 stdlib
    'unittest', 'doctest', 'pdb', 'profile', 'cProfile',
    'distutils', 'setuptools',
    # 不用的網路協定
    'ftplib', 'imaplib', 'poplib', 'smtplib', 'telnetlib', 'nntplib',
    # 其他
    'test', 'turtledemo', 'sqlite3',
]

# 收集 meshtastic 所有子模組，確保 USB 自動偵測正常運作
meshtastic_hidden = collect_submodules('meshtastic')

a = Analysis(
    ['mesh_bbs_client.py'],
    pathex=['.'],
    binaries=[],
    datas=[
        *collect_data_files('meshtastic'),
    ],
    hiddenimports=meshtastic_hidden + [
        'pubsub',
        'pubsub.core',
        'colorama',
        'serial',
        'serial.tools',
        'serial.tools.list_ports',
        'serial.tools.list_ports_windows',
        'google.protobuf',
        'google.protobuf.descriptor',
        'google.protobuf.descriptor_pool',
        'google.protobuf.message',
        'google.protobuf.reflection',
        'google.protobuf.symbol_database',
        'google.protobuf.internal',
        'google.protobuf.internal.builder',
    ],
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=EXCLUDES,
    win_no_prefer_redirects=False,
    win_private_assemblies=False,
    cipher=block_cipher,
    noarchive=False,
)

pyz = PYZ(a.pure, a.zipped_data, cipher=block_cipher)

exe = EXE(
    pyz,
    a.scripts,
    a.binaries,
    a.zipfiles,
    a.datas,
    [],
    name='MeshtasticBBS_Client',
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=True,
    upx_exclude=[],
    runtime_tmpdir=None,
    console=True,
    disable_windowed_traceback=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
    icon=None,
)
