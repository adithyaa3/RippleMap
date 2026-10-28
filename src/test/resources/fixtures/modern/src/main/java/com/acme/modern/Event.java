package com.acme.modern;

public sealed interface Event permits Created, Deleted {
}
