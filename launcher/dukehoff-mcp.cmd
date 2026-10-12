@REM
@REM Copyright 2026 Dukehoff GmbH
@REM SPDX-License-Identifier: Apache-2.0
@REM

@echo off
rem Starts dukehoff-mcp from a Windows console: dukehoff-mcp.cmd [options] [workspace]. Agents should not use
rem this file (clients start .cmd files unreliably); register java.exe -jar dukehoff-mcp.jar instead,
rem which "dukehoff-mcp.cmd --config vscode" prints.
setlocal
set "DUKEHOFF_MCP_JAVA=java"
if defined JAVA_HOME set "DUKEHOFF_MCP_JAVA=%JAVA_HOME%\bin\java.exe"
if defined DUKEHOFF_MCP_JDK set "DUKEHOFF_MCP_JAVA=%DUKEHOFF_MCP_JDK%\bin\java.exe"
"%DUKEHOFF_MCP_JAVA%" -jar "%~dp0dukehoff-mcp.jar" %*
