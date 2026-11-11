SHELL := /bin/bash
.SHELLFLAGS := -eu -o pipefail -c
.DEFAULT_GOAL := help

BOOTSTRAP_CACHE ?= $(HOME)/.cache/seasonvar/downloads
ENV := ./scripts/android-env.sh
GRADLE_ARGS ?=
VERSION_NAME_SUFFIX ?=
DEV_COMMIT ?=
LOG_ARGS ?=
INPUT_TEXT ?=
SEARCH_QUERY ?= Игра престолов
export INPUT_TEXT SEARCH_QUERY
NETWORK_DELAY ?= none
KEY_ARGS ?=
GRADLE := $(ENV) ./gradlew --console=plain $(if $(strip $(VERSION_NAME_SUFFIX)),-PversionNameSuffix="$(VERSION_NAME_SUFFIX)") $(GRADLE_ARGS)
AVD ?= Seasonvar_TV_API_34
DISPLAY_SIZE ?= reset
DISPLAY_DENSITY ?= reset
EMULATOR_ARGS := $(shell python3 -c 'import json; print(" ".join(json.load(open("toolchain.lock.json"))["avd"]["emulator_args"]))')
DEVICE ?= emulator-5554
APP_ID := ru.seasonvar.tv
PROFILE_BUILD_TYPE := $(shell python3 -c 'import json; print(json.load(open("toolchain.lock.json"))["performance"]["build_type"])')
PROFILE_OUT ?= build/profile-navigation
PROFILE_ARGS ?=
MARK_MENU_PROBE_OUT ?= build/mark-menu-probe
SERIES_LIST_PROBE_OUT ?= build/series-list-probe
SHARED_CARD_CACHE_PROBE_OUT ?= build/shared-card-cache-probe
CACHE_MEMORY_PROBE_OUT ?= build/cache-memory-probe
LAYOUT_PROBE_MODE ?= mark-menu-layout
DETAILS_PROGRESS_PROBE_OUT ?= build/details-progress-probe
BUILD_TYPE ?= debug
BUILD_VARIANT := $(shell python3 -c 'import sys; print(sys.argv[1].capitalize())' "$(BUILD_TYPE)")
APK := app/build/outputs/apk/$(BUILD_TYPE)/app-$(BUILD_TYPE).apk
SIGNER_JAR := $(shell python3 -c 'import json; print(json.load(open("toolchain.lock.json"))["release"]["apksigner_jar"])')
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
PLAYER_PROBE_PAGE ?= https://seasonvar.ru/serial-2779--Naruto_pssrhvg-0-season.html
PLAYER_PROBE_OUT ?= build/player-probe
PLAYER_PROBE_ARGS ?=
PROXY_PROBE_AUTH ?= true
PROXY_PROBE_PROTOCOL ?= http_connect
HISTORY_PROBE_OUT ?= build/history-probe
UI_SCREENS_PROBE_OUT ?= build/ui-screens-probe
HISTORY_CLEAR_PROBE_OUT ?= build/history-clear-probe
HISTORY_FILTERS_PROBE_OUT ?= build/history-filters-probe
PUBLIC_FILTERS_PROBE_OUT ?= build/public-filters-probe
BEST_FILTERS_PROBE_OUT ?= build/best-filters-probe
PERSONAL_FILTERS_PROBE_OUT ?= build/personal-filters-probe
HOME_PROBE_OUT ?= build/home-probe
PLAYER_PROBE_SEASON_PAGE ?= https://seasonvar.ru/serial-32752-Lyubov__smert__i_roboty_psxknda-3-season.html
UPDATE_PREVIEW_SCENARIO ?= new
MARK_PROBE_MODE ?= inspect
SMART_PROBE_SEASON ?=
ACCOUNT_MARK_RUNNER := $(shell python3 -c 'import json; print(json.load(open("toolchain.lock.json"))["verification"]["account_mark_runner"])')

.PHONY: emulator-display smart-continue-probe mark-menu-probe status-probe player-probe update-restore update-test update-fixture update-probe update-preview check-resolved-dependencies check-build-crypto dependency-report build-environment wrapper help bootstrap licenses doctor deps build release release-dev release-package ci-prepare ci-lint test lint check clean devices avd emulator emulator-stop emulator-restart install install-apk run restart logs debug attach key key-double tap screenshot layout site-fetch site-test site-styles site-scripts site-updates site-top site-newest site-best site-announcements site-search text build-profile run-profile profile-navigation emulator-network-delay site-video-probe account-test mark-probe ci-tools ci-export-env codeql-build

.PHONY: home-probe home-platform

help:
	@printf '%s\n' \
		'make check-resolved-dependencies Verify patched dependencies across the full graph' \
		'make dependency-report      Reproduce the complete CI dependency graph locally' \
		'make build-environment      Inspect resolved build-tool dependencies' \
		'make wrapper                Regenerate wrapper files for the pinned Gradle version' \
		'make bootstrap              Install the pinned JDK and SDK locally' \
		'make licenses               Review Android SDK licenses' \
		'make doctor                 Check local tools and versions' \
		'make deps                   Download build dependencies' \
		'make build                  Build the debug APK' \
		'make release                Build and sign the release APK using project secrets.env' \
		'make release-dev            Build a release-signed APK with dev + current commit suffix' \
		'make release-package        Prepare versioned release APK and SHA256SUMS' \
		'make ci-tools               Install the pinned build tools without compiling the app' \
		'make ci-prepare             Install build tools, accept SDK licenses and fetch dependencies' \
		'make codeql-build           Compile app and signing tool for CodeQL without signing' \
		'make ci-export-env          Export pinned tool paths to GitHub Actions' \
		'make ci-lint                Validate GitHub Actions workflows with pinned actionlint' \
		'make build-profile          Build an optimized profileable APK' \
		'make run-profile DEVICE=... Install and launch the profile build' \
		'make profile-navigation     Measure menu navigation (PROFILE_OUT=...)' \
		'make test                   Run the unit-test task' \
		'make lint                   Run Android Lint' \
		'make smart-continue-probe   Read-only account smart-list verification in emulator' \
		'make mark-menu-probe        Verify stable mark-menu layout and focus offline in emulator' \
		'make series-list-probe      Verify shared renderer on 33,000 offline entries in emulator' \
		'make shared-card-cache-probe Verify cross-list card reuse, logout and configured cache capacity' \
		'make cache-memory-probe     Measure native card cache and poster memory in emulator' \
		'make details-progress-probe Verify stable progress layout across loading states offline' \
		'make status-probe           Verify live list status changes and restore the original mark (emulator)' \
		'make player-probe           Verify video, clocks, autoplay and controls in emulator; inspect frames' \
		'make history-probe          Record Naruto episode one through the emulator player; inspect frames' \
		'make ui-screens-probe       Inspect screens and dialogs using local fixtures' \
		'make button-layout-probe    Verify shared button text alignment and dialog states' \
		'make history-clear-probe    Verify history confirmation using offline fixtures' \
		'make history-filters-probe  Research authenticated history controls without writes' \
		'make public-filters-probe   Research public filters in guest and account contexts' \
		'make best-filters-probe     Check live best-list switches without changing account marks' \
		'make personal-filters-probe Check live genre/country queries without changing account marks' \
		'make home-probe             Verify home channels, links and video in emulator; inspect frames' \
		'make home-platform          Inspect launcher and channel approval support on DEVICE' \
		'make update-test            Run offline update tests' \
		'make update-probe           Check live GitHub metadata and APK integrity in emulator' \
		'make tls-pins-probe         Check real TLS chains and reject wrong pins in emulator' \
		'make update-restore         Restore normal test build after an update preview (emulator only)' \
		'make update-preview         Preview update flow with a newer test APK in emulator' \
		'make account-test           Check account, personal lists, cookies and private cache' \
		'make account-premium-probe  Verify premium expiration across authenticated pages in emulator' \
		'make mark-probe             Inspect account marks (season-messages reads season captions; roundtrip/delete-roundtrip verify and restore changes)' \
		'make check                  Build, run unit tests and Android Lint' \
		'make avd                    Create the local Android TV emulator' \
		'make emulator               Start the local emulator window' \
		'make emulator-display       Set test size/density (DISPLAY_SIZE=1920x1080 DISPLAY_DENSITY=240; reset by default)'
		'make emulator-network-delay Set emulator latency (NETWORK_DELAY=1000 or none)' \
		'make emulator-restart       Restart and apply emulator input settings' \
		'make install-apk APK=... [DEVICE=serial] Install an existing APK' \
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
		'make site-announcements     Fetch the public upcoming series page' \
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

release:
	$(MAKE) build BUILD_TYPE=release
	$(ENV) sh -c 'exec java --class-path "$$ANDROID_HOME/$(SIGNER_JAR)" scripts/SignRelease.java app/build/outputs/apk/release/app-release-unsigned.apk app/build/outputs/apk/release/app-release.apk'

release-dev:
	@commit="$(DEV_COMMIT)"; \
		if [[ -z "$$commit" ]]; then commit="$$(git rev-parse --short=7 HEAD)"; fi; \
		[[ "$$commit" =~ ^[0-9a-f]{7,40}$$ ]] || { echo 'Expected a Git commit hash.' >&2; exit 1; }; \
		$(MAKE) release VERSION_NAME_SUFFIX="-dev$$commit"

release-package: release
	python3 scripts/package-release.py

ci-tools:
	python3 scripts/bootstrap.py --build-only --cache-dir "$(BOOTSTRAP_CACHE)"
	@printf 'y\n%.0s' {1..100} | $(ENV) sh -c 'exec sdkmanager --sdk_root="$$ANDROID_HOME" --licenses'

ci-export-env:
	$(ENV) sh -c 'test -n "$${GITHUB_ENV:-}" || { echo "This target requires GitHub Actions GITHUB_ENV" >&2; exit 1; }; printf "%s\n" "JAVA_HOME=$$JAVA_HOME" "ANDROID_HOME=$$ANDROID_HOME" "ANDROID_SDK_ROOT=$$ANDROID_SDK_ROOT" >> "$$GITHUB_ENV"'

ci-prepare: ci-tools
	$(MAKE) deps

codeql-build:
	$(GRADLE) --no-daemon --no-build-cache --no-configuration-cache --rerun-tasks -Pkotlin.incremental=false -Pkotlin.compiler.execution.strategy=in-process :app:assembleDebug
	$(ENV) sh -c 'mkdir -p build/security; exec javac --class-path "$$ANDROID_HOME/$(SIGNER_JAR)" -d build/security scripts/SignRelease.java'

ci-lint:
	python3 scripts/lint-workflows.py --cache-dir "$(BOOTSTRAP_CACHE)"

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

check: site-test check-resolved-dependencies
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

emulator-display:
	@case "$(DEVICE)" in emulator-*) ;; *) echo 'Use an emulator for display verification.' >&2; exit 1;; esac
	@[[ "$(DISPLAY_SIZE)" == reset || "$(DISPLAY_SIZE)" =~ ^[1-9][0-9]{2,4}x[1-9][0-9]{2,4}$$ ]] || { echo 'Expected DISPLAY_SIZE=WIDTHxHEIGHT or reset.' >&2; exit 1; }
	@[[ "$(DISPLAY_DENSITY)" == reset || "$(DISPLAY_DENSITY)" =~ ^[1-9][0-9]{1,3}$$ ]] || { echo 'Expected DISPLAY_DENSITY=dpi or reset.' >&2; exit 1; }
	$(ADB) shell wm size "$(DISPLAY_SIZE)"
	$(ADB) shell wm density "$(DISPLAY_DENSITY)"

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
	$(MAKE) install-apk

install-apk:
	test -f "$(APK)"
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

site-announcements:
	$(MAKE) site-fetch SITE_URL='https://seasonvar.ru/?mode=soon' SITE_OUT=build/site/announcements.html

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
	curl --fail --silent --show-error --location --max-time 20 \
		'https://cdn.svstat.net/asset/js/pg.marks.min.js?$(SITE_SCRIPT_VERSION)' -o build/site/pg.marks.js

status-probe:
	@case "$(DEVICE)" in emulator-*) ;; *) echo 'Use an emulator for status verification.' >&2; exit 1;; esac
	$(MAKE) mark-probe MARK_PROBE_MODE=status-roundtrip

site-video-probe:
	python3 scripts/probe-video.py --url "$(SITE_URL)" --translation "$(SITE_TRANSLATION)" \
		--episode "$(SITE_EPISODE)" --strategy "$(SITE_STRATEGY)" --timeout "$(SITE_TIMEOUT)"

account-test:
	$(GRADLE) :app:testDebugUnitTest --tests 'ru.seasonvar.tv.data.account.*' --tests 'ru.seasonvar.tv.ui.account.*' --tests 'ru.seasonvar.tv.data.playback.AccountMark*'

.PHONY: account-premium-probe
account-premium-probe:
	@case "$(DEVICE)" in emulator-*) ;; *) echo 'Use an emulator for premium probes.' >&2; exit 1;; esac
	$(MAKE) devices
	$(MAKE) install BUILD_TYPE=debug
	$(GRADLE) :app:assembleDebugAndroidTest
	$(ADB) install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
	$(ENV) python3 scripts/probe-account-premium.py --device "$(DEVICE)" --runner "$(APP_ID).test/$(ACCOUNT_MARK_RUNNER)"

mark-probe:
	$(MAKE) devices
	$(MAKE) install BUILD_TYPE=debug
	$(GRADLE) :app:assembleDebugAndroidTest
	$(ADB) install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
	$(ENV) python3 scripts/probe-account-mark.py --device "$(DEVICE)" --mode "$(MARK_PROBE_MODE)" --runner "$(APP_ID).test/$(ACCOUNT_MARK_RUNNER)"

wrapper:
	$(GRADLE) wrapper --network-timeout 60000

build-environment:
	$(GRADLE) buildEnvironment :app:buildEnvironment

dependency-report:
	python3 scripts/prefetch-gradle.py --cache-dir "$(BOOTSTRAP_CACHE)"
	$(GRADLE) --no-configuration-cache --no-configure-on-demand --init-script gradle/dependency-report.init.gradle :ForceDependencyResolutionPlugin_resolveAllDependencies

check-resolved-dependencies: dependency-report
	python3 scripts/check-resolved-dependencies.py

check-build-crypto: check-resolved-dependencies

update-test:
	$(GRADLE) :app:testDebugUnitTest --tests 'ru.seasonvar.tv.data.update.*' --tests 'ru.seasonvar.tv.ui.update.*'

.PHONY: proxy-live-probe
proxy-live-probe:
	@case "$(DEVICE)" in emulator-*) ;; *) echo 'Use an emulator for proxy probes.' >&2; exit 1;; esac
	$(MAKE) devices
	$(MAKE) install BUILD_TYPE=debug
	$(GRADLE) :app:assembleDebugAndroidTest
	$(ADB) install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
	$(ADB) shell am force-stop "$(APP_ID)"
	$(ENV) python3 scripts/probe-proxy.py --device "$(DEVICE)" --runner "$(APP_ID).test/$(ACCOUNT_MARK_RUNNER)" --page "$(PLAYER_PROBE_PAGE)" --season-page "$(PLAYER_PROBE_SEASON_PAGE)" --output "$(PLAYER_PROBE_OUT)" --auth "$(PROXY_PROBE_AUTH)" --protocol "$(PROXY_PROBE_PROTOCOL)"

.PHONY: connection-checks-probe
.PHONY: pac-engine-probe
pac-engine-probe:
	$(MAKE) mark-menu-probe LAYOUT_PROBE_MODE=pac-engine MARK_MENU_PROBE_OUT="$(UI_SCREENS_PROBE_OUT)"

.PHONY: pac-live-probe
pac-live-probe:
	$(MAKE) mark-menu-probe LAYOUT_PROBE_MODE=pac-live MARK_MENU_PROBE_OUT="$(UI_SCREENS_PROBE_OUT)"

.PHONY: pac-no-webview-probe
pac-no-webview-probe:
	@case "$(DEVICE)" in emulator-*) ;; *) echo 'Use an emulator for PAC probes.' >&2; exit 1;; esac
	$(MAKE) devices
	$(MAKE) install BUILD_TYPE=debug
	$(GRADLE) :app:assembleDebugAndroidTest
	$(ADB) install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
	$(ADB) shell am force-stop "$(APP_ID)"
	$(ENV) python3 scripts/probe-pac-webview.py --device "$(DEVICE)" --runner "$(APP_ID).test/$(ACCOUNT_MARK_RUNNER)"

connection-checks-probe:
	$(MAKE) mark-menu-probe LAYOUT_PROBE_MODE=connection-checks MARK_MENU_PROBE_OUT="$(UI_SCREENS_PROBE_OUT)"

.PHONY: proxy-settings-probe
proxy-settings-probe:
	$(MAKE) mark-menu-probe LAYOUT_PROBE_MODE=proxy-settings MARK_MENU_PROBE_OUT="$(UI_SCREENS_PROBE_OUT)"

.PHONY: tls-pins-probe
tls-pins-probe:
	@case "$(DEVICE)" in emulator-*) ;; *) echo 'Use an emulator for TLS probes.' >&2; exit 1;; esac
	$(MAKE) devices
	$(MAKE) install BUILD_TYPE=debug
	$(GRADLE) :app:assembleDebugAndroidTest
	$(ADB) install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
	$(ENV) python3 scripts/probe-tls-pins.py --device "$(DEVICE)" --runner "$(APP_ID).test/$(ACCOUNT_MARK_RUNNER)"

update-fixture:
	python3 scripts/update-fixture.py

update-probe:
	@case "$(DEVICE)" in emulator-*) ;; *) echo 'Use an emulator for update probes.' >&2; exit 1;; esac
	$(MAKE) devices
	$(MAKE) update-restore BUILD_TYPE=debug
	$(GRADLE) :app:assembleDebugAndroidTest
	$(ADB) install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
	$(ADB) shell am instrument -w -e mode update-inspect "$(APP_ID).test/$(ACCOUNT_MARK_RUNNER)"

update-preview:
	@case "$(DEVICE)" in emulator-*) ;; *) echo 'Use an emulator for update previews.' >&2; exit 1;; esac
	@case "$(UPDATE_PREVIEW_SCENARIO)" in new|current|network|signature|notes-network) ;; *) echo 'Unknown update scenario.' >&2; exit 1;; esac
	$(MAKE) devices
	$(MAKE) update-fixture
	$(MAKE) update-restore BUILD_TYPE=debug
	$(GRADLE) :app:assembleDebugAndroidTest
	$(ADB) install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
	$(ADB) push build/update-probe/fixture.apk /data/local/tmp/seasonvar-update-fixture.apk
	$(ADB) shell am force-stop "$(APP_ID)"
	$(ADB) shell am instrument -e mode update-preview -e scenario "$(UPDATE_PREVIEW_SCENARIO)" "$(APP_ID).test/$(ACCOUNT_MARK_RUNNER)"

update-restore:
	@case "$(DEVICE)" in emulator-*) ;; *) echo 'Test version rollback is allowed only in emulator.' >&2; exit 1;; esac
	$(MAKE) devices
	$(MAKE) build
	$(ADB) install -r -d "$(APK)"

home-platform:
	$(MAKE) devices
	$(ADB) shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
	$(ADB) shell cmd package resolve-activity --brief -a android.media.tv.action.REQUEST_CHANNEL_BROWSABLE

home-probe:
	@case "$(DEVICE)" in emulator-*) ;; *) echo 'Use an emulator for home channel verification.' >&2; exit 1;; esac
	$(MAKE) devices
	$(MAKE) install BUILD_TYPE=debug
	$(GRADLE) :app:assembleDebugAndroidTest
	$(ADB) install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
	$(ADB) shell am force-stop "$(APP_ID)"
	$(ENV) python3 scripts/probe-home.py --device "$(DEVICE)" --runner "$(APP_ID).test/$(ACCOUNT_MARK_RUNNER)" --output "$(HOME_PROBE_OUT)"

.PHONY: history-probe
.PHONY: history-filters-probe
history-filters-probe:
	@case "$(DEVICE)" in emulator-*) ;; *) echo 'Use an emulator for filter verification.' >&2; exit 1;; esac
	$(MAKE) devices
	$(MAKE) install BUILD_TYPE=debug
	$(GRADLE) :app:assembleDebugAndroidTest
	$(ADB) install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
	$(ADB) shell am force-stop "$(APP_ID)"
	$(ENV) python3 scripts/probe-history-filters.py --device "$(DEVICE)" --runner "$(APP_ID).test/$(ACCOUNT_MARK_RUNNER)" --output "$(HISTORY_FILTERS_PROBE_OUT)"

.PHONY: public-filters-probe
public-filters-probe:
	@case "$(DEVICE)" in emulator-*) ;; *) echo 'Use an emulator for filter verification.' >&2; exit 1;; esac
	$(MAKE) devices
	$(MAKE) install BUILD_TYPE=debug
	$(GRADLE) :app:assembleDebugAndroidTest
	$(ADB) install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
	$(ADB) shell am force-stop "$(APP_ID)"
	$(ENV) python3 scripts/probe-public-filters.py --device "$(DEVICE)" --runner "$(APP_ID).test/$(ACCOUNT_MARK_RUNNER)" --output "$(PUBLIC_FILTERS_PROBE_OUT)"

.PHONY: best-filters-probe
best-filters-probe:
	@case "$(DEVICE)" in emulator-*) ;; *) echo 'Use an emulator for filter verification.' >&2; exit 1;; esac
	$(MAKE) devices
	$(MAKE) install BUILD_TYPE=debug
	$(GRADLE) :app:assembleDebugAndroidTest
	$(ADB) install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
	$(ADB) shell am force-stop "$(APP_ID)"
	$(ENV) python3 scripts/probe-best-filters.py --device "$(DEVICE)" --runner "$(APP_ID).test/$(ACCOUNT_MARK_RUNNER)" --output "$(BEST_FILTERS_PROBE_OUT)"

.PHONY: personal-filters-probe
personal-filters-probe:
	@case "$(DEVICE)" in emulator-*) ;; *) echo 'Use an emulator for filter verification.' >&2; exit 1;; esac
	$(MAKE) devices
	$(MAKE) install BUILD_TYPE=debug
	$(GRADLE) :app:assembleDebugAndroidTest
	$(ADB) install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
	$(ADB) shell am force-stop "$(APP_ID)"
	$(ENV) python3 scripts/probe-personal-filters.py --device "$(DEVICE)" --runner "$(APP_ID).test/$(ACCOUNT_MARK_RUNNER)" --output "$(PERSONAL_FILTERS_PROBE_OUT)"

history-probe:
	@case "$(DEVICE)" in emulator-*) ;; *) echo 'Use an emulator for history verification.' >&2; exit 1;; esac
	$(MAKE) devices
	$(MAKE) install BUILD_TYPE=debug
	$(GRADLE) :app:assembleDebugAndroidTest
	$(ADB) install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
	$(ADB) shell am force-stop "$(APP_ID)"
	$(ENV) python3 scripts/probe-history.py --device "$(DEVICE)" --runner "$(APP_ID).test/$(ACCOUNT_MARK_RUNNER)" --output "$(HISTORY_PROBE_OUT)"

player-probe:
	@case "$(DEVICE)" in emulator-*) ;; *) echo 'Use an emulator for the player probe.' >&2; exit 1;; esac
	$(MAKE) devices
	$(MAKE) install BUILD_TYPE=debug
	$(GRADLE) :app:assembleDebugAndroidTest
	$(ADB) install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
	$(ADB) shell am force-stop "$(APP_ID)"
	$(ENV) python3 scripts/probe-player.py --device "$(DEVICE)" --runner "$(APP_ID).test/$(ACCOUNT_MARK_RUNNER)" --page "$(PLAYER_PROBE_PAGE)" --season-page "$(PLAYER_PROBE_SEASON_PAGE)" --output "$(PLAYER_PROBE_OUT)" $(PLAYER_PROBE_ARGS)

.PHONY: pac-cancellation-probe
pac-cancellation-probe:
	$(MAKE) mark-menu-probe LAYOUT_PROBE_MODE=pac-cancellation MARK_MENU_PROBE_OUT="$(UI_SCREENS_PROBE_OUT)"

.PHONY: ui-dialogs-probe
ui-dialogs-probe:
	$(MAKE) mark-menu-probe LAYOUT_PROBE_MODE=ui-dialogs-layout MARK_MENU_PROBE_OUT="$(UI_SCREENS_PROBE_OUT)"

.PHONY: ui-screens-probe
ui-screens-probe:
	$(MAKE) mark-menu-probe LAYOUT_PROBE_MODE=ui-screens-layout MARK_MENU_PROBE_OUT="$(UI_SCREENS_PROBE_OUT)"

.PHONY: button-layout-probe
.PHONY: catalog-live-probe
.PHONY: catalog-layout-probe
catalog-layout-probe:
	$(MAKE) mark-menu-probe LAYOUT_PROBE_MODE=catalog-layout MARK_MENU_PROBE_OUT="$(CATALOG_LAYOUT_PROBE_OUT)"

CATALOG_LAYOUT_PROBE_OUT ?= build/catalog-layout-probe

catalog-live-probe:
	@case "$(DEVICE)" in emulator-*) ;; *) echo 'Use an emulator for catalog verification.' >&2; exit 1;; esac
	$(MAKE) devices
	$(MAKE) install BUILD_TYPE=debug
	$(GRADLE) :app:assembleDebugAndroidTest
	$(ADB) install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
	$(ADB) shell am force-stop "$(APP_ID)"
	$(ENV) python3 scripts/probe-catalog.py --device "$(DEVICE)" --runner "$(APP_ID).test/$(ACCOUNT_MARK_RUNNER)"

button-layout-probe: history-clear-probe

.PHONY: history-clear-probe
history-clear-probe:
	$(MAKE) mark-menu-probe LAYOUT_PROBE_MODE=history-clear-layout MARK_MENU_PROBE_OUT="$(HISTORY_CLEAR_PROBE_OUT)"

.PHONY: details-progress-probe
.PHONY: series-list-probe
.PHONY: shared-card-cache-probe
.PHONY: cache-memory-probe
cache-memory-probe:
	$(MAKE) mark-menu-probe LAYOUT_PROBE_MODE=cache-memory MARK_MENU_PROBE_OUT="$(CACHE_MEMORY_PROBE_OUT)"

shared-card-cache-probe:
	$(MAKE) mark-menu-probe LAYOUT_PROBE_MODE=shared-card-cache MARK_MENU_PROBE_OUT="$(SHARED_CARD_CACHE_PROBE_OUT)"

series-list-probe:
	$(MAKE) mark-menu-probe LAYOUT_PROBE_MODE=series-list-engine MARK_MENU_PROBE_OUT="$(SERIES_LIST_PROBE_OUT)"

details-progress-probe:
	$(MAKE) mark-menu-probe LAYOUT_PROBE_MODE=details-progress-layout MARK_MENU_PROBE_OUT="$(DETAILS_PROGRESS_PROBE_OUT)"

mark-menu-probe:
	@case "$(DEVICE)" in emulator-*) ;; *) echo 'Use an emulator for menu verification.' >&2; exit 1;; esac
	$(MAKE) devices
	$(MAKE) install BUILD_TYPE=debug
	$(GRADLE) :app:assembleDebugAndroidTest
	$(ADB) install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
	$(ADB) shell am force-stop "$(APP_ID)"
	$(ENV) python3 scripts/probe-mark-menu.py --device "$(DEVICE)" --runner "$(APP_ID).test/$(ACCOUNT_MARK_RUNNER)" --mode "$(LAYOUT_PROBE_MODE)" --output "$(MARK_MENU_PROBE_OUT)"

smart-continue-probe:
	@case "$(DEVICE)" in emulator-*) ;; *) echo 'Use an emulator for smart-list verification.' >&2; exit 1;; esac
	$(MAKE) devices
	$(MAKE) install BUILD_TYPE=debug
	$(GRADLE) :app:assembleDebugAndroidTest
	$(ADB) install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
	$(ADB) shell am force-stop "$(APP_ID)"
	$(ENV) python3 scripts/probe-account-mark.py --device "$(DEVICE)" --runner "$(APP_ID).test/$(ACCOUNT_MARK_RUNNER)" --mode smart-inspect --target-season "$(SMART_PROBE_SEASON)"
