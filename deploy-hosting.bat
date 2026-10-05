@echo off
cd /d "%~dp0"
echo.
echo === AGUA & GAS EXPRESS - PUBLICAR CONVITE ===
echo Pasta atual: %CD%
echo.
if not exist firebase.json (
  echo ERRO: firebase.json nao foi encontrado nesta pasta.
  echo Extraia o ZIP inteiro e execute este arquivo dentro da pasta extraida.
  pause
  exit /b 1
)
where firebase >nul 2>nul
if errorlevel 1 (
  echo ERRO: Firebase CLI nao esta instalado ou nao esta no PATH.
  echo Instale o Firebase CLI e tente novamente.
  pause
  exit /b 1
)
call firebase deploy --only hosting
if errorlevel 1 (
  echo.
  echo FALHA no deploy. A mensagem acima indica o motivo.
  pause
  exit /b 1
)
echo.
echo DEPLOY DO CONVITE CONCLUIDO.
echo Hosting: https://agua-e-gas-express.web.app
pause
