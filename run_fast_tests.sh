#!/bin/bash

# Script to run the Serengeti fast test suite

echo "Running Serengeti Fast Test Suite..."
echo "-----------------------------------"

# Run the fast tests using Maven
mvn test -Pfast-tests

# Check the exit code
if [ $? -eq 0 ]; then
    echo "-----------------------------------"
    echo "Fast Test Suite completed successfully!"
else
    echo "-----------------------------------"
    echo "Fast Test Suite failed!"
    exit 1
fi