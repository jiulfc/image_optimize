SDK ?= $(HOME)/Library/Android/sdk
ADB = $(SDK)/platform-tools/adb
GRADLE_VERSION = 8.7
GRADLE = gradle-dist/gradle-$(GRADLE_VERSION)/bin/gradle
PACKAGE = com.codinglibs.imageserver
APK = app/build/outputs/apk/debug/app-debug.apk

JAVA_HOME ?= $(shell for h in /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
	/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home; do \
	[ -x "$$h/bin/java" ] && echo "$$h" && exit 0; done)
export JAVA_HOME

.PHONY: build install run uninstall clean

build: check-java $(GRADLE) local.properties
	$(GRADLE) assembleDebug

install: build
	$(ADB) install -r $(APK)

run: install
	$(ADB) shell am start -n $(PACKAGE)/.MainActivity

uninstall:
	$(ADB) uninstall $(PACKAGE)

clean:
	rm -rf app/build .gradle local.properties

check-java:
	@[ -n "$(JAVA_HOME)" ] && [ -x "$(JAVA_HOME)/bin/java" ] || \
		(echo "JDK 17 not found. Install it first:"; echo "  brew install openjdk@17"; exit 1)

$(GRADLE):
	@mkdir -p gradle-dist
	@cd gradle-dist && curl -fL -o gradle.zip \
		https://services.gradle.org/distributions/gradle-$(GRADLE_VERSION)-bin.zip \
		&& unzip -q gradle.zip && rm gradle.zip

local.properties:
	@echo "sdk.dir=$(SDK)" > local.properties
