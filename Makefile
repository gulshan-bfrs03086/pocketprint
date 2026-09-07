# A wrapper over ./gradlew and scripts/, and nothing more.
#
# No build logic lives here. Gradle owns the build, the five scripts in
# scripts/ own the gates, and this file only remembers the flags, the paths and
# the order so that none of them has to be looked up again. If a target here
# ever starts deciding something the build should decide, it belongs in
# app/build.gradle.kts instead.
#
# Written for the make that ships with macOS (GNU Make 3.81), so no .ONESHELL
# and no .RECIPEPREFIX. Recipes are tab-indented and each line is its own shell.
#
# `make` with no target prints the list.

.DEFAULT_GOAL := help

# AGP requires JDK 17 and refuses newer ones with an error that never mentions
# the JDK. An explicit JAVA_HOME from the environment wins - make does not
# override an inherited variable with ?= - and otherwise macOS is asked for 17
# by name rather than inheriting whichever `java` happens to be first on PATH.
#
# The query yields nothing on Linux and on CI, where the runner sets JAVA_HOME
# itself; hence the conditional export, because exporting an empty JAVA_HOME is
# worse than exporting none - gradlew tests it for emptiness, but other tools
# treat it as a path and look for a compiler inside "/bin".
JAVA_HOME ?= $(shell /usr/libexec/java_home -v 17 2>/dev/null)
ifneq ($(JAVA_HOME),)
export JAVA_HOME
endif

GRADLE_FLAGS ?=
GRADLE       := ./gradlew $(GRADLE_FLAGS)

APPLICATION_ID := com.gulshan.pocketprint
DEBUG_APK      := app/build/outputs/apk/debug/app-debug.apk
RELEASE_APK    := app/build/outputs/apk/release/app-release.apk

# Same resolution order the gate scripts use, so the Makefile and the scripts
# can never disagree about which SDK they are talking about.
ANDROID_SDK ?= $(or $(ANDROID_HOME),$(ANDROID_SDK_ROOT),$(HOME)/Library/Android/sdk)
ADB         := $(ANDROID_SDK)/platform-tools/adb

# Recursively expanded on purpose: these fork a find, and most targets never
# need them. Newest build-tools wins, which is what the scripts do too.
AAPT2     = $(shell find $(ANDROID_SDK)/build-tools -name aapt2 -type f 2>/dev/null | sort -V | tail -1)
APKSIGNER = $(shell find $(ANDROID_SDK)/build-tools -name apksigner -type f 2>/dev/null | sort -V | tail -1)

##@ Build

build: ## Assemble the debug APK
	$(GRADLE) assembleDebug

release: ## Assemble the signed release APK (needs the signing key)
	$(GRADLE) assembleRelease

release-unsigned: ## Assemble an unsigned release APK - for reading R8 output, not for installing
	$(GRADLE) assembleRelease -PallowUnsignedRelease=true

apks: ## Assemble both APKs, as CI does
	$(GRADLE) assembleDebug assembleRelease

# Gradle's outputs only. There is deliberately no target that runs
# `git clean -xfd`: everything this project keeps out of git is either
# unrecoverable or annoying to rebuild - keystore.properties holds the path to
# the signing key, local.properties holds the SDK path, and both are ignored,
# so a clean that removes ignored files removes exactly the two files nothing
# can regenerate.
clean: ## Delete Gradle's build outputs
	$(GRADLE) clean

##@ Test

test: ## Run the JVM unit tests
	$(GRADLE) testDebugUnitTest

# For the paths a JVM cannot stand in for: PdfRenderer and Bitmap, a real TCP
# stack, and the framework's own PrintAttributes. Needs a device or emulator.
test-device: ## Run the on-device instrumented tests
	$(GRADLE) connectedDebugAndroidTest

# assembleRelease already runs lintVitalRelease, which is the one that blocks a
# release. This is the wider debug run, for reading rather than for gating.
lint: ## Run Android Lint over the debug variant
	$(GRADLE) :app:lintDebug

##@ Gates

# All five, with a debug APK built first because two of them read one. Each
# gate exists because of something that already went wrong once; see the table
# in README.md.
check: build check-sources check-apks ## Run all five build gates

check-sources: check-platform-packages check-printservice-threading check-dialog-scroll ## The three gates that read sources (no build needed)

check-apks: check-required-features check-permissions ## The two gates that read built APKs

# The APK gates take a path, and default to every APK under the build output -
# debug and release both, since R8 and resource shrinking sit between the
# manifest and what actually ships. Run one of these on its own without having
# built anything and the script says so.
check-required-features: ## No REQUIRED hardware feature, which would block installation
	./scripts/check-required-features.sh

check-permissions: ## Permission set matches scripts/expected-permissions.txt
	./scripts/check-permissions.sh

check-platform-packages: ## One class inside android.print, and its fallback still wired
	./scripts/check-platform-packages.sh

check-printservice-threading: ## Print-framework handles never leave the main thread
	./scripts/check-printservice-threading.sh

check-dialog-scroll: ## Dialogs scroll instead of clipping their own buttons
	./scripts/check-dialog-scroll.sh

# What .github/workflows/ci.yml runs, in its order. Unlike `check` this builds
# the release APK, so it needs the signing key configured - which is the point:
# it is the local rehearsal of the run that gates main.
ci: test apks check-sources check-apks ## Everything CI runs, in CI's order

##@ Device

install: ## Build and install the debug APK on the attached device
	$(GRADLE) installDebug

install-release: ## Build and install the signed release APK
	$(GRADLE) installRelease

# The debug build only, and that is not an omission. Uninstalling the release
# build takes the user's configured printers with it, so removing it is a
# decision to make deliberately rather than a target to have close to hand.
uninstall: ## Remove the debug build from the attached device
	$(ADB) uninstall $(APPLICATION_ID).debug

devices: ## List attached devices
	$(ADB) devices -l

logcat: ## Follow the debug build's log output
	@pid=$$($(ADB) shell pidof -s $(APPLICATION_ID).debug 2>/dev/null | tr -d '\r'); \
	if [ -z "$$pid" ]; then \
	  echo "$(APPLICATION_ID).debug is not running - start it first (make install)." >&2; \
	  exit 1; \
	fi; \
	$(ADB) logcat --pid=$$pid

##@ Release

version: ## The version this tree builds
	@awk -F' = ' '/^val version(Major|Minor|Patch) = /{printf "%s%s", sep, $$2; sep="."} \
		END{print ""}' app/build.gradle.kts
	@echo "(the versionCode is derived from it in app/build.gradle.kts; make apk-info shows it)"

cut-release: ## Cut a version branch: make cut-release VERSION=1.4.2
	@test -n "$(VERSION)" || { echo "usage: make cut-release VERSION=1.4.2" >&2; exit 1; }
	./scripts/cut-release.sh $(VERSION)

# Prints the keystore path, the alias and the certificate fingerprints. Never a
# password: the release config only appears once the passwords are readable, so
# an absent release row means they are not set, not that signing is broken.
signing-report: ## Show the signing configuration Gradle resolved
	$(GRADLE) :app:signingReport

# "Verified using v1 scheme: false" is expected and not a problem: apksigner
# only exercises the schemes needed at the APK's own minSdk, and from API 24
# v2 is enough. Pass --min-sdk-version 21 to see v1 verify.
verify: ## Print the release APK's signing certificate
	@test -f $(RELEASE_APK) || { \
	  echo "no signed release APK at $(RELEASE_APK) - run: make release" >&2; exit 1; }
	$(APKSIGNER) verify --verbose --print-certs $(RELEASE_APK)

apk-info: ## Version, permissions and features of every built APK
	@apks=$$(find app/build/outputs/apk -path '*/androidTest/*' -prune -o \
	  -name '*.apk' -print 2>/dev/null | sort); \
	if [ -z "$$apks" ]; then echo "nothing built yet - run: make build" >&2; exit 1; fi; \
	for apk in $$apks; do \
	  echo "== $$apk"; \
	  $(AAPT2) dump badging "$$apk" | \
	    grep -E "^(package|uses-permission|uses-feature|sdkVersion|targetSdkVersion)" | \
	    sed 's/^/  /'; \
	done

##@ Meta

doctor: ## Check the toolchain this Makefile depends on
	@echo "JAVA_HOME      $${JAVA_HOME:-(unset - gradlew will use whatever java is on PATH)}"
	@java -version 2>&1 | head -1 | sed 's/^/java           /'
	@echo "Android SDK    $(ANDROID_SDK)"
	@echo "aapt2          $${AAPT2:-$(AAPT2)}"
	@echo "apksigner      $(APKSIGNER)"
	@test -x $(ADB) && echo "adb            $(ADB)" || echo "adb            NOT FOUND at $(ADB)"
	@test -f keystore.properties \
	  && echo "signing        keystore.properties present (make signing-report to resolve it)" \
	  || echo "signing        no keystore.properties; release signing comes from POCKETPRINT_* env vars"

tasks: ## List every Gradle task
	$(GRADLE) tasks --all

deps: ## Print the app module's dependency tree
	$(GRADLE) :app:dependencies

help: ## Print this list
	@echo "PocketPrint - make <target>"
	@awk 'BEGIN { FS = ":.*## " } \
		/^##@/ { printf "\n%s\n", substr($$0, 5); next } \
		/^[a-zA-Z0-9_.-]+:.*## / { printf "  %-30s %s\n", $$1, $$2 }' $(MAKEFILE_LIST)

# Every target here is a command; none of them is the name of a file make could
# find, and a stray file by any of these names must not stop one from running.
.PHONY: build release release-unsigned apks clean \
	test test-device lint \
	check check-sources check-apks \
	check-required-features check-permissions check-platform-packages \
	check-printservice-threading check-dialog-scroll ci \
	install install-release uninstall devices logcat \
	version cut-release signing-report verify apk-info \
	doctor tasks deps help
