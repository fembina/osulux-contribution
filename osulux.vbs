Set fso = CreateObject("Scripting.FileSystemObject")
Set shell = CreateObject("WScript.Shell")
baseDir = fso.GetParentFolderName(WScript.ScriptFullName)

javaExe = baseDir & "\\runtime\\bin\\javaw.exe"
If Not fso.FileExists(javaExe) Then
    javaExe = "javaw"
End If

libDir = baseDir & "\\lib"
pluginDir = libDir & "\\plugins"
jarPath = baseDir & "\\osulux.jar"


cmd = "cmd /c """ & _
    """" & javaExe & """" & _
    " -Djna.library.path=""" & libDir & """" & _
    " -Dvlcj.pluginPath=""" & pluginDir & """" & _
    " -cp """ & jarPath & """ com.osuplayer.app.Launcher" & _
    """"

shell.Run cmd, 0, False
