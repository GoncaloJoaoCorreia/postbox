package com.postbox.shared.api

import io.ktor.client.engine.HttpClientEngine

expect fun defaultHttpClientEngine(): HttpClientEngine
