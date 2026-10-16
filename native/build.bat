@echo off
rem Astratto Engine - builds the native core (astratto.dll), its self-test and the example C++ gameplay module with the
rem Visual C++ compiler. No CMake needed: CMakeLists.txt beside this file does the same for anyone who has it, and for
rem other platforms.
rem   build.bat          build everything, then run the self-test
rem   build.bat notest   build only
setlocal enabledelayedexpansion
set HERE=%~dp0
set OUT=%HERE%bin\windows-x64
set OBJ=%HERE%obj

where cl >nul 2>nul
if not errorlevel 1 goto :compile
set "VSWHERE=%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe"
if not exist "%VSWHERE%" echo astratto: no Visual Studio installation found & exit /b 2
for /f "usebackq delims=" %%i in (`"%VSWHERE%" -latest -products * -property installationPath`) do set "VSROOT=%%i"
if not exist "%VSROOT%\VC\Auxiliary\Build\vcvars64.bat" echo astratto: the C++ build tools are not installed & exit /b 2
call "%VSROOT%\VC\Auxiliary\Build\vcvars64.bat" >nul

:compile
if not exist "%OUT%" mkdir "%OUT%"
if not exist "%OBJ%\lib" mkdir "%OBJ%\lib"
if not exist "%OBJ%\test" mkdir "%OBJ%\test"
if not exist "%OBJ%\module" mkdir "%OBJ%\module"

set FLAGS=/nologo /std:c++17 /O2 /EHsc /W4 /wd4324 /fp:precise /MT /DNDEBUG /utf-8 /MP /I"%HERE%include" /I"%HERE%src"
rem every .cpp under src\ is part of the library
set SRC=
for /r "%HERE%src" %%f in (*.cpp) do set SRC=!SRC! "%%f"

cl %FLAGS% /DASTRATTO_BUILD /LD %SRC% /Fo"%OBJ%\lib\\" /Fe"%OUT%\astratto.dll" /link /IMPLIB:"%OBJ%\astratto.lib" /PDB:none user32.lib winmm.lib
if errorlevel 1 ( echo astratto: the library did not build & exit /b 1 )
del /q "%OUT%\astratto.exp" 2>nul

cl %FLAGS% "%HERE%tests\selftest.cpp" "%HERE%tests\services.cpp" /Fo"%OBJ%\test\\" /Fe"%OUT%\astratto_selftest.exe" /link "%OBJ%\astratto.lib"
if errorlevel 1 ( echo astratto: the self-test did not build & exit /b 1 )

rem the example gameplay module a game can copy: a DLL the engine loads (see include\astratto\astratto_module.h)
if exist "%HERE%modules\example\example_module.cpp" (
  cl %FLAGS% /LD "%HERE%modules\example\example_module.cpp" /Fo"%OBJ%\module\\" /Fe"%OUT%\astratto_example_module.dll" /link /PDB:none
  if errorlevel 1 ( echo astratto: the example module did not build & exit /b 1 )
  del /q "%OUT%\astratto_example_module.exp" "%OUT%\astratto_example_module.lib" 2>nul
)

if /i "%1"=="notest" exit /b 0
"%OUT%\astratto_selftest.exe"
exit /b %errorlevel%
