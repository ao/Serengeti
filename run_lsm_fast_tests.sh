#!/bin/bash

# Script to run the LSM storage engine fast tests

echo "Running LSM Storage Engine Fast Tests..."
echo "---------------------------------------"

# Run the LSM fast tests using Maven
mvn test -Dtest=*LSM*FastTest,*MemTable*FastTest,*SSTable*FastTest

# Check the exit code
if [ $? -eq 0 ]; then
    echo "---------------------------------------"
    echo "LSM Fast Tests completed successfully!"
else
    echo "---------------------------------------"
    echo "LSM Fast Tests failed!"
    exit 1
fi