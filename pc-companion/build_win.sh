#!/bin/sh
# Rebuild dist/MatrixSMS-PC-win64.zip (embedded CPython 3.12 + wheels for win_amd64).
set -e; cd "$(dirname "$0")"; B=build/win; mkdir -p $B dist
[ -f $B/python-3.12.10-embed-amd64.zip ] || curl -sL -o $B/python-3.12.10-embed-amd64.zip https://www.python.org/ftp/python/3.12.10/python-3.12.10-embed-amd64.zip
pip download -q --only-binary=:all: --platform win_amd64 --python-version 3.12 --implementation cp -d $B/wheels cryptography zeroconf
rm -rf $B/MatrixSMS-PC; mkdir -p $B/MatrixSMS-PC/python/Lib/site-packages $B/MatrixSMS-PC/app
unzip -q $B/python-3.12.10-embed-amd64.zip -d $B/MatrixSMS-PC/python
for w in $B/wheels/*.whl; do unzip -q -o $w -d $B/MatrixSMS-PC/python/Lib/site-packages; done
printf 'python312.zip\r\n.\r\nLib\\site-packages\r\n..\\app\r\nimport site\r\n' > $B/MatrixSMS-PC/python/python312._pth
cp -r msms $B/MatrixSMS-PC/app/; rm -rf $B/MatrixSMS-PC/app/msms/__pycache__
cp README.md $B/MatrixSMS-PC/README.md
printf '@echo off\r\n"%%~dp0python\\python.exe" -m msms ui %%*\r\n' > $B/MatrixSMS-PC/MatrixSMS.bat
printf '@echo off\r\n"%%~dp0python\\python.exe" -m msms %%*\r\n' > $B/MatrixSMS-PC/msms.bat
printf '@echo off\r\n"%%~dp0python\\python.exe" -m msms mcp %%*\r\n' > $B/MatrixSMS-PC/msms-mcp.bat
printf '@echo off\r\nstart "" /b "%%~dp0python\\pythonw.exe" -m msms agent\r\n' > $B/MatrixSMS-PC/msms-agent.bat
rm -f dist/MatrixSMS-PC-win64.zip; python3 -c "import shutil;shutil.make_archive('dist/MatrixSMS-PC-win64','zip','$B','MatrixSMS-PC')"
ls -la dist
