FROM flink:1.17.2-java11

# Install Python 3.8+ and pip
# Also install openjdk-11-jdk-headless to ensure headers are present for pemja build
RUN apt-get update -y && \
    apt-get install -y python3 python3-pip python3-dev openjdk-11-jdk-headless && \
    ln -s /usr/bin/python3 /usr/bin/python

# Set JAVA_HOME explicitly (though base image usually has it, apt install might change paths)
# Set JAVA_HOME explicitly (though base image usually has it, apt install might change paths)
ENV JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64
# Ensure architecture specific path is correct (amd64 vs arm64) - generic java-11-openjdk should point to correct one
ENV JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64

# Upgrade pip
RUN pip3 install --upgrade pip

# Install PyFlink and dependencies
# Pinned versions for stability
RUN pip3 install apache-flink==1.17.2 \
    pandas==2.0.3 \
    scikit-optimize==0.9.0 \
    kafka-python==2.0.2 \
    pydantic==2.5.3

# Copy internal libraries if needed (optional, for now we mount them)
WORKDIR /opt/flink
