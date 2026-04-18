
# hify

运行 server：
mvn spring-boot:run -pl hify-app -Dspring-boot.run.profiles=mock

运行 前端：
cd hify-web && npm run dev


# TIPS
运行这个项目的时候，我遇到几个错。
1. 打包依赖找不到，需要先执行mvn install
mvn clean install -DskipTests
mvn clean package -DskipTests

2. maven 版本太低
brew update
brew upgrade maven