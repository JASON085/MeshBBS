# -*- mode: python ; coding: utf-8 -*-
# PyInstaller spec for BBS 伺服器 GUI (bbs_gui.py)

from PyInstaller.utils.hooks import collect_submodules, collect_data_files

block_cipher = None

EXCLUDES = [
    # 科學計算（不使用）
    'numpy', 'pandas', 'scipy', 'matplotlib', 'sklearn',
    # 其他 GUI 框架（只用 tkinter）
    'PyQt5', 'PyQt6', 'PySide2', 'PySide6', 'wx', 'gi',
    # 圖像處理
    'PIL', 'Pillow', 'cv2',
    # Jupyter / IPython
    'IPython', 'jupyter', 'notebook', 'ipykernel',
    # 不需要的 stdlib
    'unittest', 'doctest', 'pdb', 'profile', 'cProfile',
    'distutils', 'setuptools',
    # 不用的網路協定
    'ftplib', 'imaplib', 'poplib', 'smtplib', 'telnetlib', 'nntplib',
    # 其他
    'xmlrpc', 'test', 'turtledemo',
]

# 收集 meshtastic 所有子模組（含 protobuf），確保 USB 自動偵測正常運作
meshtastic_hidden = collect_submodules('meshtastic')

a = Analysis(
    ['bbs_gui.py'],
    pathex=['.'],
    binaries=[],
    datas=[
        ('bbs_client.html', '.'),
        ('admin.html',      '.'),
        *collect_data_files('meshtastic'),
    ],
    hiddenimports=meshtastic_hidden + [
        'pubsub',
        'pubsub.core',
        'aiohttp',
        'aiohttp.web',
        'aiohttp.connector',
        'aiohttp.client',
        'aiohttp.web_runner',
        'asyncio',
        'sqlite3',
        'serial',
        'serial.tools',
        'serial.tools.list_ports',
        'serial.tools.list_ports_windows',
        'tkinter',
        'tkinter.ttk',
        'tkinter.messagebox',
        'tkinter.scrolledtext',
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
    name='MeshtasticBBS_Server',
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,
    upx_exclude=[],
    runtime_tmpdir=None,
    console=False,
    disable_windowed_traceback=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
    icon=None,
)
