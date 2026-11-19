/*
 * Copyright (C) 2026 Resurrection Remix
 *
 * Duck RKP follows Duck-ToolBox by Eltavine (MIT),
 * https://github.com/eltavine/Duck-ToolBox
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.spoof;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigInteger;

import co.nstant.in.cbor.CborDecoder;
import co.nstant.in.cbor.CborException;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.Map;
import co.nstant.in.cbor.model.Number;
import co.nstant.in.cbor.model.UnsignedInteger;

/**
 * Turns a Qualcomm V2 reply into the AuthenticatedRequest array that is
 * posted to Google. The reply is either that array or a one-entry map whose
 * key 61 holds it.
 *
 * <p>The DICE chain is not checked here. Conscrypt has no Ed25519
 * {@code KeyFactory}, so a client-side check throws
 * {@code NoSuchAlgorithmException} on a chain whose root is Ed25519 and the
 * CSR is dropped for a V1 request Google rejects. The remote provisioning
 * server checks the chain before it signs.
 */
final class DuckRkpNative {
    private static final BigInteger KEY_CSR = BigInteger.valueOf(61);

    private DuckRkpNative() {}

    static byte[] csrFromV2(byte[] response) throws IOException {
        if (response == null || response.length == 0) {
            throw new IOException("empty V2 CSR");
        }
        // CBOR array: already the AuthenticatedRequest.
        if ((response[0] & 0xe0) == 0x80) {
            return response;
        }
        final DataItem item;
        try {
            item = new CborDecoder(new ByteArrayInputStream(response)).decodeNext();
        } catch (CborException e) {
            throw new IOException("V2 CSR is not CBOR", e);
        }
        if (!(item instanceof Map)) {
            throw new IOException("V2 CSR is not an AuthenticatedRequest");
        }
        final Map wrapper = (Map) item;
        if (wrapper.getKeys().size() != 1) {
            throw new IOException("V2 CSR map is not a single field");
        }
        final DataItem key = wrapper.getKeys().iterator().next();
        if (!(key instanceof Number) || !KEY_CSR.equals(((Number) key).getValue())) {
            throw new IOException("V2 CSR map is missing field 61");
        }
        final DataItem value = wrapper.get(new UnsignedInteger(KEY_CSR.longValue()));
        if (!(value instanceof ByteString)) {
            throw new IOException("V2 CSR field 61 is not a byte string");
        }
        final byte[] csr = ((ByteString) value).getBytes();
        if (csr == null || csr.length == 0 || (csr[0] & 0xe0) != 0x80) {
            throw new IOException("V2 CSR field 61 is not an AuthenticatedRequest");
        }
        return csr;
    }
}
