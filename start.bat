@echo off
REM Carpeta donde está el bat
set BASE_DIR=%~dp0

REM DLLs de VLC y Discord
set DLL_PATH=%BASE_DIR%lib
set VLC_PLUGIN_PATH=%DLL_PATH%\plugins

REM Añadimos DLL_PATH a PATH para que Java/JNA encuentre las DLLs
set PATH=%DLL_PATH%;%PATH%

REM Ejecutar el jar indicando a JNA y vlcj dónde están las librerías
java -Djna.library.path="%DLL_PATH%" -Dvlcj.pluginPath="%VLC_PLUGIN_PATH%" -jar "%BASE_DIR%target\osulux.jar"

pause