#!/usr/bin/env bash

# Prompt the user.
printf "Enter a name: "

# Read input and trim whitespace.
read -r input
input=$(printf "%s" "$input" | awk '{$1=$1;print}')

# Respond based on the input (case-insensitive format).
shopt -s nocasematch
case "$input" in
    hello)
        echo "hello world"
        ;;
    *)
        echo "$input"
        ;;
esac
shopt -u nocasematch
