@echo off
set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
set "PATH=%JAVA_HOME%\bin;%PATH%"
call gradlew.bat assembleDebug -Djavax.net.ssl.trustStore=local_cacerts -Djavax.net.ssl.trustStorePassword=changeit
