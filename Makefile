SHELL := /bin/bash
.SHELLFLAGS := -eu -o pipefail -c
.DEFAULT_GOAL := help

BOOTSTRAP_CACHE ?= $(HOME)/.cache/seasonvar/downloads
ENV := ./scripts/android-env.sh
GRADLE_ARGS ?=
LOG_ARGS ?=
INPUT_TEXT ?=
SEARCH_QUERY ?= Игра престолов
export INPUT_TEXT SEARCH_QUERY
NETWORK_DELAY ?= none
KEY_ARGS ?=
GRADLE := $(ENV) ./gradlew --console=plain $(GRADLE_ARGS)
AVD ?= Seasonvar_TV_API_34
EMULATOR_ARGS := $(shell python3 -c 'import json; print(" ".join(json.load(open("toolchain.lock.json"))["avd"]["emulator_args"]))')
DEVICE ?= emulator-5554
APP_ID := ru.seasonvar.tv
PROFILE_BUILD_TYPE := $(shell python3 -c 'import json; print(json.load(open("toolchain.lock.json"))["performance"]["build_type"])')
PROFILE_OUT ?= build/profile-navigation
PROFILE_ARGS ?=
BUILD_TYPE ?= debug
BUILD_VARIANT := $(shell python3 -c 'import sys; print(sys.argv[1].capitalize())' "$(BUILD_TYPE)")
APK := app/build/outputs/apk/$(BUILD_TYPE)/app-$(BUILD_TYPE).apk
ADB := $(ENV) adb -s "$(DEVICE)"
SITE_URL ?= https://seasonvar.ru/
SITE_OUT ?= build/site/index.html
SITE_STRATEGY ?= default
SITE_SOURCE_IP ?=
SITE_IP ?=
SITE_TIMEOUT ?= 10
SITE_TRANSLATION ?= 0
SITE_EPISODE ?= 1
SITE_STYLE_VERSION ?= 25.4.1
SITE_SCRIPT_VERSION ?= 25.4.1

.PHONY: help bootstrap licenses doctor deps build test lint check clean devices avd emulator emulator-stop emulator-restart install run restart logs debug attach key key-double tap screenshot layout site-fetch site-test site-styles site-scripts site-updates site-top site-newest site-best site-search text build-profile run-profile profile-navigation emulator-network-delay site-video-probe

help:
	@printf '%s\n' \
		'make bootstrap              Install the pinned JDK and SDK locally' \
		'make licenses               Review Android SDK licenses' \
		'make doctor                 Check local tools and versions' \
		'make deps                   Download build dependencies' \
		'make build                  Build the debug APK' \
		'make build-profile          Build an optimized profileable APK' \
		'make run-profile DEVICE=... Install and launch the profile build' \
		'make profile-navigation     Measure menu navigation (PROFILE_OUT=...)' \
		'make test                   Run the unit-test task' \
		'make lint                   Run Android Lint' \
		'make check                  Build, run unit tests and Android Lint' \
		'make avd                    Create the local Android TV emulator' \
		'make emulator               Start the local emulator window' \
		'make emulator-network-delay Set emulator latency (NETWORK_DELAY=1000 or none)' \
		'make emulator-restart       Restart and apply emulator input settings' \
		'make run [DEVICE=serial]     Build, install and launch' \
		'make restart                Restart the installed app' \
		'make logs [LOG_ARGS=-d]      Show app-only logs' \
		'make debug [DEVICE=serial]   Open a local JDWP debugger port' \
		'make attach                 Attach the terminal debugger' \
		'make text INPUT_TEXT=hello  Enter text in the focused input' \
		'make key KEY=BACK           Send a remote-control key' \
		'make key-double KEY=BACK    Send a quick double key press' \
		'make tap X=400 Y=670        Tap a point in device pixels' \
		'make layout                 Dump the current UI hierarchy' \
		'make screenshot OUT=file    Save the current screen' \
		'make devices                List connected devices' \
		'make site-fetch             Fetch public Seasonvar HTML/JSON with DPI strategy' \
		'make site-video-probe       Probe a real episode (SITE_URL=exact series page)' \
		'make site-updates           Fetch the public updates AJAX fragment' \
		'make site-top               Fetch the public Top AJAX fragment' \
		'make site-newest            Fetch the public Newest AJAX fragment' \
		'make site-search            Fetch search JSON (SEARCH_QUERY=...)' \
		'make site-best              Fetch the public Best Series page' \
		'make site-test              Test the standalone website fetcher' \
		'make site-styles            Download original website CSS for theme reference' \
		'make site-scripts           Download website JavaScript for protocol research'

bootstrap:
	python3 scripts/bootstrap.py --cache-dir "$(BOOTSTRAP_CACHE)"

licenses:
	$(ENV) sh -c 'exec sdkmanager --sdk_root="$$ANDROID_HOME" --licenses'

doctor:
	$(ENV) java -version
	$(GRADLE) --version
	$(ENV) adb version

deps:
	python3 scripts/prefetch-gradle.py --cache-dir "$(BOOTSTRAP_CACHE)"
	$(GRADLE) :app:assembleDebug

build:
	$(GRADLE) :app:assemble$(BUILD_VARIANT)

build-profile:
	$(MAKE) build BUILD_TYPE=$(PROFILE_BUILD_TYPE)

profile-navigation:
	python3 scripts/profile-navigation.py --device "$(DEVICE)" --output "$(PROFILE_OUT)" $(PROFILE_ARGS)

run-profile:
	$(MAKE) run BUILD_TYPE=$(PROFILE_BUILD_TYPE)

test: site-test
	$(GRADLE) :app:testDebugUnitTest

lint:
	$(GRADLE) :app:lintDebug

check: site-test
	$(GRADLE) :app:assembleDebug :app:testDebugUnitTest :app:lintDebug

clean:
	$(GRADLE) clean

devices:
	$(ENV) adb devices -l

avd:
	@if $(ENV) emulator -list-avds | tr -d '\r' | grep -Fxq "$(AVD)"; then echo "AVD $(AVD) is ready."; else \
	printf 'no\n' | $(ENV) avdmanager create avd --name "$(AVD)" \
		--package "$$(python3 -c 'import platform; print("system-images;android-34;android-tv;" + ("arm64-v8a" if platform.machine() in ("arm64", "aarch64") else "x86"))')" \
		--device tv_1080p; fi
	python3 scripts/configure-avd.py --name "$(AVD)"

emulator: avd
	@if $(ADB) emu avd name 2>/dev/null | tr -d '\r' | grep -Fxq "$(AVD)"; then \
		echo "AVD $(AVD) is already running on $(DEVICE). Run make run."; \
	else $(ENV) emulator -avd "$(AVD)" $(EMULATOR_ARGS); fi

emulator-network-delay:
	$(ADB) emu network delay "$(NETWORK_DELAY)"

emulator-stop:
	@if ! name="$$($(ADB) emu avd name 2>/dev/null | tr -d '\r' | head -n 1)"; then \
		echo 'Emulator is already stopped.'; exit 0; fi; \
		[[ "$$name" == "$(AVD)" ]] || { echo 'Selected device is a different emulator.' >&2; exit 1; }; \
		$(ADB) emu kill; \
		for attempt in {1..30}; do \
			if ! $(ADB) get-state >/dev/null 2>&1; then exit 0; fi; sleep 1; \
		done; echo 'Emulator did not stop within 30 seconds.' >&2; exit 1

emulator-restart: emulator-stop
	$(MAKE) emulator

install: build
	$(ADB) wait-for-device
	@until [[ "$$($(ADB) shell getprop sys.boot_completed | tr -d '\r')" == 1 ]]; do sleep 1; done
	$(ADB) install -r "$(APK)"

run: install
	$(ADB) shell am start -n "$(APP_ID)/.MainActivity"

restart:
	$(ADB) shell am force-stop "$(APP_ID)"
	$(ADB) shell am start -n "$(APP_ID)/.MainActivity"

logs:
	@pid="$$($(ADB) shell pidof -s "$(APP_ID)" | tr -d '\r')"; \
		test -n "$$pid" || { echo 'The app is not running.' >&2; exit 1; }; \
		$(ADB) logcat --pid="$$pid" $(LOG_ARGS)

debug:
	@pid="$$($(ADB) shell pidof -s "$(APP_ID)" | tr -d '\r')"; \
		test -n "$$pid" || { echo 'Run make run first.' >&2; exit 1; }; \
		$(ADB) forward tcp:8700 "jdwp:$$pid"; \
		echo 'Attach Android Studio to the app or run make attach.'

attach: debug
	$(ENV) jdb -attach localhost:8700

key:
	@test -n "$(KEY)" || { echo 'Set KEY=DPAD_CENTER, BACK, DPAD_UP, ...' >&2; exit 1; }
	$(ADB) shell input keyevent $(KEY_ARGS) "KEYCODE_$(KEY)"

text:
	@test -n "$${INPUT_TEXT:-}" || { echo 'Set INPUT_TEXT to enter in the focused field.' >&2; exit 1; }
	$(ENV) python3 -c 'import os,shlex,subprocess,sys; subprocess.run(["adb", "-s", sys.argv[1], "shell", "input", "text", shlex.quote(os.environ["INPUT_TEXT"].replace(" ", "%s"))], check=True)' "$(DEVICE)"

key-double:
	@test -n "$(KEY)" || { echo 'Set KEY=BACK or ESCAPE' >&2; exit 1; }
	$(ADB) shell input keyevent "KEYCODE_$(KEY)" "KEYCODE_$(KEY)"

tap:
	@test -n "$(X)" -a -n "$(Y)" || { echo 'Set X and Y in device pixels.' >&2; exit 1; }
	$(ADB) shell input tap "$(X)" "$(Y)"

screenshot:
	@test -n "$(OUT)" || { echo 'Set OUT=/path/to/screenshot.png' >&2; exit 1; }
	$(ADB) exec-out screencap -p > "$(OUT)"

layout:
	$(ADB) shell uiautomator dump /data/local/tmp/seasonvar-ui.xml > /dev/null
	$(ADB) shell cat /data/local/tmp/seasonvar-ui.xml

site-fetch:
	python3 scripts/fetch-seasonvar.py --url "$(SITE_URL)" --output "$(SITE_OUT)" \
		--strategy "$(SITE_STRATEGY)" --timeout "$(SITE_TIMEOUT)" \
		$(if $(SITE_SOURCE_IP),--source-ip "$(SITE_SOURCE_IP)") $(if $(SITE_IP),--ip "$(SITE_IP)")

site-updates:
	python3 scripts/fetch-seasonvar.py --sidebar new --output build/site/updates.html \
		--strategy "$(SITE_STRATEGY)" --timeout "$(SITE_TIMEOUT)" \
		$(if $(SITE_SOURCE_IP),--source-ip "$(SITE_SOURCE_IP)") $(if $(SITE_IP),--ip "$(SITE_IP)")

site-top:
	python3 scripts/fetch-seasonvar.py --sidebar pop --output build/site/top.html \
		--strategy "$(SITE_STRATEGY)" --timeout "$(SITE_TIMEOUT)" \
		$(if $(SITE_SOURCE_IP),--source-ip "$(SITE_SOURCE_IP)") $(if $(SITE_IP),--ip "$(SITE_IP)")

site-newest:
	python3 scripts/fetch-seasonvar.py --sidebar newest --output build/site/newest.html \
		--strategy "$(SITE_STRATEGY)" --timeout "$(SITE_TIMEOUT)" \
		$(if $(SITE_SOURCE_IP),--source-ip "$(SITE_SOURCE_IP)") $(if $(SITE_IP),--ip "$(SITE_IP)")

site-best:
	$(MAKE) site-fetch SITE_URL='https://seasonvar.ru/?mode=top' SITE_OUT=build/site/best.html

site-search:
	@url="$$(python3 -c 'import os,urllib.parse; print("https://seasonvar.ru/autocomplete.php?" + urllib.parse.urlencode({"query": os.environ["SEARCH_QUERY"]}))')"; \
		$(MAKE) site-fetch SITE_URL="$$url" SITE_OUT=build/site/search.json

site-test:
	python3 -m unittest discover -s tests -p 'test_*.py' -v

site-styles:
	mkdir -p build/site
	curl --fail --silent --show-error --location --max-time 20 \
		'https://cdn.svstat.net/asset/css/main.css?$(SITE_STYLE_VERSION)' -o build/site/main.css
	curl --fail --silent --show-error --location --max-time 20 \
		'https://cdn.svstat.net/asset/css/pg.index.css?$(SITE_STYLE_VERSION)' -o build/site/pg.index.css

site-scripts:
	mkdir -p build/site
	curl --fail --silent --show-error --location --max-time 20 \
		'https://cdn.svstat.net/asset/js/main.min.js?$(SITE_SCRIPT_VERSION)' -o build/site/main.js

site-video-probe:
	python3 scripts/probe-video.py --url "$(SITE_URL)" --translation "$(SITE_TRANSLATION)" \
		--episode "$(SITE_EPISODE)" --strategy "$(SITE_STRATEGY)" --timeout "$(SITE_TIMEOUT)"
