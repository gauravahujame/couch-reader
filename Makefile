.PHONY: check device-test engine-server
check:
	./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest

device-test:
	./gradlew :app:connectedDebugAndroidTest

engine-server:
	python3 tests/reader_server.py
