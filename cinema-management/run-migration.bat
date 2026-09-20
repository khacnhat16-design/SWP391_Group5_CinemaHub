@echo off
cd /d "%~dp0"
call mvnw.cmd compile exec:java -Dexec.mainClass=com.cinema.upload.UploadThingMigration
