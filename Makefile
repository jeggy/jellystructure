# Auto-detect the JBR installed by IntelliJ IDEA, falling back to any JDK in ~/.jdks or system JAVA_HOME.
JAVA_HOME ?= $(or \
    $(shell find $(HOME)/.jdks -maxdepth 1 -type d -name "jbr-*" 2>/dev/null | sort -rV | head -1), \
    $(shell find $(HOME)/.jdks -maxdepth 1 -type d                2>/dev/null | sort -rV | head -1))
export JAVA_HOME
export PATH := $(JAVA_HOME)/bin:$(PATH)

GRADLEW := ./gradlew --no-daemon

.PHONY: dev-backend dev-frontend build clean help

help: ## Show available targets
	@awk 'BEGIN {FS = ":.*?## "} /^[a-zA-Z_-]+:.*?## / {printf "  %-18s %s\n", $$1, $$2}' $(MAKEFILE_LIST)

dev-backend: ## Terminal 1 — build and run the backend server (port 9505)
	$(GRADLEW) runBackend

dev-frontend: ## Terminal 2 — start webpack dev server with hot reload (opens http://localhost:8080)
	$(GRADLEW) wasmJsBrowserDevelopmentRun

build: ## Build release artifacts (backend binary + frontend distribution)
	$(GRADLEW) linkReleaseExecutableLinuxX64 wasmJsBrowserProductionExecutableDistribution

clean: ## Delete all build artifacts
	$(GRADLEW) clean
