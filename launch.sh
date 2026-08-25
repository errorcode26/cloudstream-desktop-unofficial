#!/usr/bin/env bash
# CloudStream Desktop Launcher for Linux & macOS

set -e
cd "$(dirname "$0")"

ACTION="${1:-}"

check_submodules() {
    if [ ! -f "android-reference/settings.gradle.kts" ]; then
        echo "[INFO] android-reference submodule is empty. Attempting to fetch automatically..."
        git submodule update --init --recursive
        if [ ! -f "android-reference/settings.gradle.kts" ]; then
            echo ""
            echo "[FATAL ERROR] The android-reference folder is STILL empty!"
            echo "Please clone the repository recursively:"
            echo "  git clone --recursive <repo-url>"
            exit 1
        fi
    fi
}

show_menu() {
    clear
    echo "==================================================="
    echo "          CloudStream Desktop Launcher (Linux)     "
    echo "==================================================="
    echo ""
    echo "  [1] Start CloudStream (Default Dev Mode)"
    echo "  [2] Start with Dev Studio & Live LogCat (--dev)"
    echo "  [3] Build Distributable Package (Linux / DEB / RPM)"
    echo "  [4] Run All Tests & Health Checks"
    echo ""
    echo "  [Q] Exit"
    echo ""
    echo "==================================================="
    read -rp "Select option [1-4] (Default is 1): " CHOICE

    case "${CHOICE:-1}" in
        1) run_normal ;;
        2) run_dev ;;
        3) run_build ;;
        4) run_test ;;
        q|Q) exit 0 ;;
        *) echo "Invalid selection."; sleep 1; show_menu ;;
    esac
}

run_normal() {
    check_submodules
    echo ""
    echo "[INFO] Starting CloudStream Desktop Client..."
    echo "[TIP] Press F12 in-app anytime to open the Dev Studio LogCat."
    echo ""
    ./gradlew :desktop-app:run
}

run_dev() {
    check_submodules
    echo ""
    echo "[INFO] Starting CloudStream Desktop with Dev Studio & Live LogCat..."
    echo ""
    ./gradlew :desktop-app:run --args="--dev"
}

run_build() {
    check_submodules
    echo ""
    echo "==================================================="
    echo "   Compiling CloudStream Desktop Package           "
    echo "==================================================="
    echo ""
    ./gradlew clean :desktop-app:createDistributable
    echo "[SUCCESS] Distributable package created!"
}

run_test() {
    check_submodules
    echo ""
    echo "[INFO] Running all unit test suites and verifications..."
    echo ""
    ./gradlew :common:test :plugin-runtime:test :desktop-app:compileKotlin
}

case "$ACTION" in
    dev) run_dev ;;
    build|compile) run_build ;;
    test) run_test ;;
    start|"") show_menu ;;
    *) show_menu ;;
esac
