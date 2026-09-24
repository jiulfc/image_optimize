# project

Media server

## logs

`adb logcat -d -b crash | tail -60`

## run

`make run`

## router

`http://<phone>` image server
`http://<phone>?type=video` video server

## auth flow

session level token  
session timeout 30 minutes

user -> server -> grant -> token  
user -> token -> server

## without Wi-Fi

`adb forward tcp:8080 tcp:8080`
