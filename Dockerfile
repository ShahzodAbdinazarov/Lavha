# JDK 21, not 17: gradle/gradle-daemon-jvm.properties pins the daemon to Java 21, so the image has
# to actually contain a Java 21 or every task here fails to start. Keep the two in sync.
FROM gradle:8.13.0-jdk21

ENV ANDROID_SDK_URL https://dl.google.com/android/repository/commandlinetools-linux-7302050_latest.zip
ENV ANDROID_API_LEVEL android-36
ENV ANDROID_BUILD_TOOLS_VERSION 36.0.0
ENV ANDROID_HOME /usr/local/android-sdk-linux
ENV ANDROID_NDK_VERSION 21.4.7075529
ENV ANDROID_VERSION 36
ENV ANDROID_NDK_HOME ${ANDROID_HOME}/ndk/${ANDROID_NDK_VERSION}/
ENV PATH ${PATH}:${ANDROID_HOME}/tools:${ANDROID_HOME}/platform-tools

ENV ANDROID_HOME=/usr/local/android-sdk-linux

ENV ANDROID_API_LEVEL=android-36
ENV ANDROID_VERSION=36
ENV ANDROID_BUILD_TOOLS_VERSION=36.0.0

ENV ANDROID_NDK_VERSION=27.2.12479018
ENV ANDROID_NDK_HOME=${ANDROID_HOME}/ndk/${ANDROID_NDK_VERSION}

ENV PATH=${PATH}:${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools
ENV PATH=${PATH}:${ANDROID_NDK_HOME}
ENV PATH=${PATH}:${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64/bin

RUN mkdir -p "${ANDROID_HOME}/cmdline-tools" /home/gradle/.android && \
    cd /tmp && \
    curl -fL "${ANDROID_SDK_URL}" -o commandlinetools.zip && \
    unzip commandlinetools.zip && \
    mv cmdline-tools "${ANDROID_HOME}/cmdline-tools/latest" && \
    rm commandlinetools.zip

RUN yes | sdkmanager --sdk_root="${ANDROID_HOME}" --licenses
RUN sdkmanager \
    --sdk_root="${ANDROID_HOME}" \
    "build-tools;36.0.0" \
    "build-tools;${ANDROID_BUILD_TOOLS_VERSION}" \
    "platforms;android-${ANDROID_VERSION}" \
    "platform-tools" \
    "ndk;$ANDROID_NDK_VERSION"
RUN cp $ANDROID_HOME/build-tools/30.0.3/dx $ANDROID_HOME/build-tools/${ANDROID_BUILD_TOOLS_VERSION}/dx
RUN cp $ANDROID_HOME/build-tools/30.0.3/lib/dx.jar $ANDROID_HOME/build-tools/${ANDROID_BUILD_TOOLS_VERSION}/lib/dx.jar
ENV PATH ${ANDROID_NDK_HOME}:$PATH
ENV PATH ${ANDROID_NDK_HOME}/prebuilt/linux-x86_64/bin/:$PATH

CMD mkdir -p /home/source/TMessagesProj/build/outputs/apk && \
    mkdir -p /home/gradle/TMessagesProj/build/outputs/bundle && \
    mkdir -p /home/source/TMessagesProj/build/outputs/native-debug-symbols && \
    cp -R /home/source/. /home/gradle && \
    cd /home/gradle && \
    gradle --parallel \
        :TMessagesProj_App:bundleBundleAfat_SDK23Release \
        :TMessagesProj_App:bundleBundleAfatRelease \
        :TMessagesProj_AppStandalone:assembleAfatStandalone \
        :TMessagesProj_App:assembleAfatRelease \
        :TMessagesProj_AppHuawei:assembleAfatRelease --stacktrace && \
    cp -R /home/gradle/TMessagesProj_App/build/outputs/apk/. /home/source/TMessagesProj/build/outputs/apk && \
    cp -R /home/gradle/TMessagesProj_AppHuawei/build/outputs/apk/. /home/source/TMessagesProj/build/outputs/apk && \
    cp -R /home/gradle/TMessagesProj_AppStandalone/build/outputs/apk/. /home/source/TMessagesProj/build/outputs/apk && \
    cp -R /home/gradle/TMessagesProj_App/build/outputs/bundle/. /home/source/TMessagesProj/build/outputs/bundle
