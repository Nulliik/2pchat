# Set ANDROID_NDK_HOME (or pass NDK_DIR=/path/to/ndk) in local and CI builds.
# Do not hard-code a developer's macOS SDK path: emulator builds also run on Windows.
NDK_DIR ?= $(ANDROID_NDK_HOME)

ifeq ($(OS),Windows_NT)
NDK_HOST_TAG ?= windows-x86_64
TOOLCHAIN_EXE_SUFFIX ?= .cmd
else
UNAME_S := $(shell uname -s)
ifeq ($(UNAME_S),Darwin)
NDK_HOST_TAG ?= darwin-x86_64
else
NDK_HOST_TAG ?= linux-x86_64
endif
TOOLCHAIN_EXE_SUFFIX ?=
endif

ifeq ($(strip $(NDK_DIR)),)
$(error Set ANDROID_NDK_HOME or pass NDK_DIR=/path/to/android-ndk)
endif

TOOLCHAIN = $(NDK_DIR)/toolchains/llvm/prebuilt/$(NDK_HOST_TAG)/bin
API_LEVEL = 24
JNILIBS_DIR = ../app/src/main/jniLibs
CGO_CFLAGS ?= -fno-omit-frame-pointer -O2

.PHONY: all test clean android-arm64 android-x86_64 android-arm android-all

all: test android-all

test:
	go test -v ./pkg/...

android-arm64:
	mkdir -p $(JNILIBS_DIR)/arm64-v8a
	CGO_ENABLED=1 \
	CGO_CFLAGS="$(CGO_CFLAGS)" \
	GOOS=android \
	GOARCH=arm64 \
	CC=$(TOOLCHAIN)/aarch64-linux-android$(API_LEVEL)-clang$(TOOLCHAIN_EXE_SUFFIX) \
	go build -ldflags="-s -w" -buildmode=c-shared -o $(JNILIBS_DIR)/arm64-v8a/lib2pcore.so ./cmd/lib2pcore
	@rm -f $(JNILIBS_DIR)/arm64-v8a/lib2pcore.h
	@echo "✓ Built arm64-v8a: $(JNILIBS_DIR)/arm64-v8a/lib2pcore.so"

android-x86_64:
	mkdir -p $(JNILIBS_DIR)/x86_64
	CGO_ENABLED=1 \
	CGO_CFLAGS="$(CGO_CFLAGS)" \
	GOOS=android \
	GOARCH=amd64 \
	CC=$(TOOLCHAIN)/x86_64-linux-android$(API_LEVEL)-clang$(TOOLCHAIN_EXE_SUFFIX) \
	go build -ldflags="-s -w" -buildmode=c-shared -o $(JNILIBS_DIR)/x86_64/lib2pcore.so ./cmd/lib2pcore
	@rm -f $(JNILIBS_DIR)/x86_64/lib2pcore.h
	@echo "✓ Built x86_64: $(JNILIBS_DIR)/x86_64/lib2pcore.so"

android-arm:
	mkdir -p $(JNILIBS_DIR)/armeabi-v7a
	CGO_ENABLED=1 \
	CGO_CFLAGS="$(CGO_CFLAGS)" \
	GOOS=android \
	GOARCH=arm \
	GOARM=7 \
	CC=$(TOOLCHAIN)/armv7a-linux-androideabi$(API_LEVEL)-clang$(TOOLCHAIN_EXE_SUFFIX) \
	go build -ldflags="-s -w" -buildmode=c-shared -o $(JNILIBS_DIR)/armeabi-v7a/lib2pcore.so ./cmd/lib2pcore
	@rm -f $(JNILIBS_DIR)/armeabi-v7a/lib2pcore.h
	@echo "✓ Built armeabi-v7a: $(JNILIBS_DIR)/armeabi-v7a/lib2pcore.so"

android-all: android-arm64 android-x86_64 android-arm
	@echo "✓ All Android native binaries built successfully."

clean:
	rm -rf $(JNILIBS_DIR)/arm64-v8a/lib2pcore.so $(JNILIBS_DIR)/x86_64/lib2pcore.so $(JNILIBS_DIR)/armeabi-v7a/lib2pcore.so
