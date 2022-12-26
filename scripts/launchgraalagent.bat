echo "Setting up MSVC"
call "C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat"
echo "moving to build dir"
cd C:\Users\secon\Projects\retro-lsp\
echo "Executing "
call mvnw.cmd -Pnative -Dagent=true -DskipTests package