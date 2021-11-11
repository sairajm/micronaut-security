/*
 * Copyright 2017-2020 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.security.token.jwt.signature.jwks;

import com.nimbusds.jose.Algorithm;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyType;
import com.nimbusds.jwt.SignedJWT;
import io.micronaut.context.annotation.EachBean;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.security.token.jwt.signature.SignatureConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.URL;
import java.text.ParseException;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Signature configuration which enables verification of remote JSON Web Key Set.
 *
 * A bean of this class is created for each {@link io.micronaut.security.token.jwt.signature.jwks.JwksSignatureConfiguration}.
 *
 * @author Sergio del Amo
 * @since 1.1.0
 */
@EachBean(JwksSignatureConfiguration.class)
public class JwksSignature implements JwksCache, SignatureConfiguration {

    @Deprecated
    public static final int DEFAULT_REFRESH_JWKS_ATTEMPTS = 1;

    private static final Logger LOG = LoggerFactory.getLogger(JwksSignature.class);

    private final JwkValidator jwkValidator;

    private JWKSet jwkSet;
    private final KeyType keyType;
    private final String url;

    /**
     *
     * @param jwksSignatureConfiguration JSON Web Key Set configuration.
     * @param jwkValidator JWK Validator to be used.
     */
    @Inject
    public JwksSignature(JwksSignatureConfiguration jwksSignatureConfiguration,
                         JwkValidator jwkValidator) {
        this(jwksSignatureConfiguration.getUrl(), jwksSignatureConfiguration.getKeyType(), jwkValidator);
    }

    /**
     * @param url The JWK url
     * @param keyType The JWK key type
     * @param jwkValidator JWK Validator to be used.
     */
    public JwksSignature(String url,
                         @Nullable KeyType keyType,
                         JwkValidator jwkValidator) {
        this.url = url;
        if (LOG.isDebugEnabled()) {
            LOG.debug("JWT validation URL: {}", url);
        }
        this.keyType = keyType;
        this.jwkValidator = jwkValidator;
    }

    private Optional<JWKSet> getJWKSet() {
        JWKSet jwkSet = this.jwkSet;
        if (jwkSet == null) {
            synchronized (this) { // double check
                jwkSet = this.jwkSet;
                if (jwkSet == null) {
                    jwkSet = loadJwkSet(getUrl());
                    this.jwkSet = jwkSet;
                }
            }
        }
        return Optional.ofNullable(jwkSet);
    }

    private List<JWK> getJsonWebKeys() {
        return getJWKSet().map(JWKSet::getKeys).orElse(Collections.emptyList());
    }

    /**
     *
     * @return A message indicating the supported algorithms.
     */
    @Override
    public String supportedAlgorithmsMessage() {
        String message = getJsonWebKeys().stream()
                .map(JWK::getAlgorithm)
                .map(Algorithm::getName)
                .reduce((a, b) -> a + ", " + b)
                .map(s -> "Only the " + s)
                .orElse("No");
        return message + " algorithms are supported";
    }

    /**
     * Whether this signature configuration supports this algorithm.
     *
     * @param algorithm the signature algorithm
     * @return whether this signature configuration supports this algorithm
     */
    @Override
    public boolean supports(JWSAlgorithm algorithm) {
        return getJsonWebKeys()
                .stream()
                .map(JWK::getAlgorithm)
                .anyMatch(algorithm::equals);
    }

    /**
     * Verify a signed JWT.
     *
     * @param jwt the signed JWT
     * @return whether the signed JWT is verified
     * @throws JOSEException exception when verifying the JWT
     */
    @Override
    public boolean verify(SignedJWT jwt) throws JOSEException {
        List<JWK> matches = matches(jwt, getJWKSet().orElse(null));
        if (LOG.isDebugEnabled()) {
            LOG.debug("Found {} matching JWKs", matches.size());
        }
        if (matches == null || matches.isEmpty()) {
            return false;
        }
        return verify(matches, jwt);
    }

    @Override
    public void clearJsonWebKeySet() {
        this.jwkSet = null;
    }

    @Override
    public boolean isJsonWebKeySetPresent() {
        return this.jwkSet != null;
    }

    /**
     * Instantiates a JWKSet for a give url.
     * @param url JSON Web Key Set Url.
     * @return a JWKSet or null if there was an error.
     */
    @Nullable
    protected JWKSet loadJwkSet(String url) {
        if (url == null) {
            return null;
        }
        try {
            return JWKSet.load(new URL(url));
        } catch (IOException | ParseException e) {
            if (LOG.isErrorEnabled()) {
                LOG.error("Exception loading JWK from " + url + ". The JwksSignature will not be used to verify a JWT if further refresh attempts fail", e);
            }
        }

        return null;
    }

    /**
     * Calculates a list of JWK matches for a JWT.
     *
     * Since the JWTSet is cached it attempts to refresh it (by calling its self recursive)
     * if the {@code refreshKeysAttempts} is greater than 0.
     *
     * @param jwt A Signed JWT
     * @param jwkSet A JSON Web Key Set
     * @param refreshKeysAttempts Number of times to attempt refreshing the JWK Set
     * @deprecated Use {@link JwksSignature#matches(SignedJWT, JWKSet)} instead
     * @return a List of JSON Web Keys
     */
    @Deprecated
    protected List<JWK> matches(SignedJWT jwt, @Nullable JWKSet jwkSet, int refreshKeysAttempts) {
        return matches(jwt, jwkSet);
    }

    /**
     * Calculates a list of JWK matches for a JWT.
     *
     * Since the JWTSet is cached it attempts to refresh it (by calling its self recursive)
     * if the {@code refreshKeysAttempts} is greater than 0.
     *
     * @param jwt A Signed JWT
     * @param jwkSet A JSON Web Key Set
     * @return a List of JSON Web Keys
     */
    protected List<JWK> matches(SignedJWT jwt, @Nullable JWKSet jwkSet) {
        List<JWK> matches = Collections.emptyList();
        if (jwkSet != null) {
            JWKMatcher.Builder builder = new JWKMatcher.Builder();
            if (keyType != null) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Key Type: {}", keyType);
                }
                builder = builder.keyType(keyType);
            }
            String keyId = jwt.getHeader().getKeyID();
            if (LOG.isDebugEnabled()) {
                LOG.debug("JWT Key ID: {}", keyId);
            }
            if (LOG.isDebugEnabled()) {
                LOG.debug("JWK Set Key IDs: {}", String.join(",", getJsonWebKeySetKeyIDs()));
            }
            if (keyId != null) {
                builder = builder.keyID(keyId);
            }

            matches = new JWKSelector(builder.build()).select(jwkSet);
        }
        return matches;
    }

    @Override
    @NonNull
    public List<String> getJsonWebKeySetKeyIDs() {
        return jwkSet == null ? Collections.emptyList() : jwkSet.getKeys()
                .stream()
                .map(JWK::getKeyID)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * returns true if any JWK match is able to verify the JWT signature.
     *
     * @param matches A List of JSON Web key matches.
     * @param jwt A JWT to be verified.
     * @return true if the JWT signature could be verified.
     */
    protected boolean verify(List<JWK> matches, SignedJWT jwt) {
        return matches.stream().anyMatch(jwk -> jwkValidator.validate(jwt, jwk));
    }

    /**
     * Returns the number of attempts to refresh the cached JWKS.
     * @return Number of attempts to refresh the cached JWKS.
     */
    @Deprecated
    public int getRefreshJwksAttempts() {
        return DEFAULT_REFRESH_JWKS_ATTEMPTS;
    }
    
    /**
     *
     * @return A JSON Web Key Validator.
     */
    public JwkValidator getJwkValidator() {
        return jwkValidator;
    }

    /**
     *
     * @return a JSON Web Key Set.
     */
    public JWKSet getJwkSet() {
        return jwkSet;
    }

    /**
     *
     * @return the Key Type.
     */
    public KeyType getKeyType() {
        return keyType;
    }

    /**
     *
     * @return The JSON Web Key Set (JWKS) URL.
     */
    public String getUrl() {
        return url;
    }
}
