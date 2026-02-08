# ORB-SLAM3 Docker Image
# Based on Ubuntu 20.04 with CUDA support (optional)

FROM ubuntu:20.04

# Prevent interactive prompts during package installation
ENV DEBIAN_FRONTEND=noninteractive
ENV TZ=Asia/Seoul

# Set working directory
WORKDIR /root

# Install basic dependencies
RUN apt-get update && apt-get install -y \
    build-essential \
    cmake \
    git \
    wget \
    unzip \
    pkg-config \
    libglew-dev \
    libboost-all-dev \
    libssl-dev \
    libeigen3-dev \
    libsuitesparse-dev \
    qtbase5-dev \
    libgtk2.0-dev \
    libavcodec-dev \
    libavformat-dev \
    libswscale-dev \
    libv4l-dev \
    libxvidcore-dev \
    libx264-dev \
    libjpeg-dev \
    libpng-dev \
    libtiff-dev \
    libatlas-base-dev \
    gfortran \
    python3-dev \
    python3-pip \
    python3-numpy \
    libgl1-mesa-dev \
    libwayland-dev \
    libxkbcommon-dev \
    wayland-protocols \
    libegl1-mesa-dev \
    && rm -rf /var/lib/apt/lists/*

# Install Pangolin (visualization library)
# Use -j2 to limit memory usage
RUN cd /root && \
    git clone --recursive https://github.com/stevenlovegrove/Pangolin.git && \
    cd Pangolin && \
    git checkout v0.6 && \
    mkdir build && cd build && \
    cmake .. -DCMAKE_BUILD_TYPE=Release && \
    make -j2 && \
    make install && \
    ldconfig

# Install OpenCV 4.4
# Use -j2 to limit memory usage
RUN cd /root && \
    wget -O opencv.zip https://github.com/opencv/opencv/archive/4.4.0.zip && \
    wget -O opencv_contrib.zip https://github.com/opencv/opencv_contrib/archive/4.4.0.zip && \
    unzip opencv.zip && \
    unzip opencv_contrib.zip && \
    cd opencv-4.4.0 && \
    mkdir build && cd build && \
    cmake -D CMAKE_BUILD_TYPE=Release \
          -D CMAKE_INSTALL_PREFIX=/usr/local \
          -D OPENCV_EXTRA_MODULES_PATH=../../opencv_contrib-4.4.0/modules \
          -D ENABLE_PRECOMPILED_HEADERS=OFF \
          -D WITH_CUDA=OFF \
          -D WITH_QT=ON \
          -D WITH_OPENGL=ON \
          -D BUILD_EXAMPLES=OFF \
          -D BUILD_TESTS=OFF \
          -D BUILD_PERF_TESTS=OFF \
          .. && \
    make -j2 && \
    make install && \
    ldconfig && \
    cd /root && rm -rf opencv.zip opencv_contrib.zip opencv-4.4.0 opencv_contrib-4.4.0

# Clone ORB-SLAM3
RUN cd /root && \
    git clone https://github.com/UZ-SLAMLab/ORB_SLAM3.git ORB_SLAM3

# Build ORB-SLAM3 with fix for C++14 compatibility
WORKDIR /root/ORB_SLAM3

# Fix CMakeLists.txt for better compatibility
RUN sed -i 's/++11/++14/g' CMakeLists.txt && \
    sed -i 's/++11/++14/g' Thirdparty/DBoW2/CMakeLists.txt && \
    sed -i 's/++11/++14/g' Thirdparty/g2o/CMakeLists.txt

# Build third-party libraries with limited parallelism
RUN cd Thirdparty/DBoW2 && \
    mkdir -p build && cd build && \
    cmake .. -DCMAKE_BUILD_TYPE=Release && \
    make -j2

RUN cd Thirdparty/g2o && \
    mkdir -p build && cd build && \
    cmake .. -DCMAKE_BUILD_TYPE=Release && \
    make -j2

# Uncompress vocabulary
RUN cd Vocabulary && \
    tar -xf ORBvoc.txt.tar.gz

# Build ORB-SLAM3 with single thread to avoid OOM
# This is the most memory-intensive step
RUN mkdir -p build && cd build && \
    cmake .. -DCMAKE_BUILD_TYPE=Release && \
    make -j1

# Set library path
ENV LD_LIBRARY_PATH=/root/ORB_SLAM3/lib:/root/ORB_SLAM3/Thirdparty/DBoW2/lib:/root/ORB_SLAM3/Thirdparty/g2o/lib

# Create directories for data
RUN mkdir -p /data/input /data/output /data/vocabulary /data/config

# Copy vocabulary to data directory
RUN cp /root/ORB_SLAM3/Vocabulary/ORBvoc.txt /data/vocabulary/

# Set working directory for runtime
WORKDIR /root/ORB_SLAM3

# Default command
CMD ["/bin/bash"]
