# Dockerfile for building the Donut Algorithm mod JAR
# Usage: docker build -t donut-mod-builder . && docker run --rm -v $(pwd)/output:/output donut-mod-builder

FROM eclipse-temurin:21-jdk-jammy

WORKDIR /build

# Copy the mod source
COPY Donut-Algorithm/ /build/Donut-Algorithm/

# Build the mod
WORKDIR /build/Donut-Algorithm
RUN chmod +x gradlew && ./gradlew build

# Copy the JAR to the output location
CMD ["cp", "build/libs/*.jar", "/output/"]
