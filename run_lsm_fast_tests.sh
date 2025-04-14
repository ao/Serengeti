#!/bin/bash

# Script to run the fast tests for Serengeti components

echo "Running Serengeti Fast Tests..."
echo "-----------------------------"

# Run the fast tests using Maven
mvn test -Dtest=*LSM*FastTest,*MemTable*FastTest,*SSTable*FastTest,*Network*FastTest

# Check the exit code
if [ $? -eq 0 ]; then
    echo "-----------------------------"
    echo "Fast Tests completed successfully!"
else
    echo "-----------------------------"
    echo "Fast Tests failed!"
    exit 1
fi