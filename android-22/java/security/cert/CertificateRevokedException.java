/*
 * Copyright 2014 The Android Open Source Project
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

package java.security.cert;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import javax.security.auth.x500.X500Principal;
import org.apache.harmony.security.x509.InvalidityDate;

/**
 * Exception that is thrown when a certificate is invalid because it has been
 * revoked.
 *
 * @since 1.7
 * @hide
 */
public class CertificateRevokedException extends CertificateException {

    private static final long serialVersionUID = 7839996631571608627L;

    private final Date revocationDate;

    private final CRLReason reason;

    private final X500Principal authority;

    // Maps may not be serializable, so serialize it manually.
    private transient Map<String, Extension> extensions;

    /**
     * @param revocationDate date the certificate was revoked
     * @param reason reason the certificate was revoked if available
     * @param authority authority that revoked the certificate
     * @param extensions X.509 extensions associated with this revocation
     */
    public CertificateRevokedException(Date revocationDate, CRLReason reason,
            X500Principal authority, Map<String, Extension> extensions) {
        this.revocationDate = revocationDate;
        this.reason = reason;
        this.authority = authority;
        this.extensions = extensions;
    }

    /**
     * Returns the principal of the authority that issued the revocation.
     */
    public X500Principal getAuthorityName() {
        return authority;
    }

    /**
     * X.509 extensions that are associated with this revocation.
     */
    public Map<String, Extension> getExtensions() {
        return Collections.unmodifiableMap(extensions);
    }

    /**
     * Returns the date when the certificate was known to become invalid if
     * available.
     */
    public Date getInvalidityDate() {
        if (extensions == null) {
            return null;
        }

        Extension invalidityDateExtension = extensions.get("2.5.29.24");
        if (invalidityDateExtension == null) {
            return null;
        }

        try {
            InvalidityDate invalidityDate = new InvalidityDate(invalidityDateExtension.getValue());
            return invalidityDate.getDate();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Returns the detail message of the thrown exception.
     */
    public String getMessage() {
        StringBuffer sb = new StringBuffer("Certificate was revoked");
        if (revocationDate != null) {
            sb.append(" on ").append(revocationDate.toString());
        }
        if (reason != null) {
            sb.append(" due to ").append(reason);
        }
        return sb.toString();
    }

    /**
     * Returns the date the certificate was revoked.
     */
    public Date getRevocationDate() {
        return (Date) revocationDate.clone();
    }

    /**
     * Returns the reason the certificate was revoked if available.
     */
    public CRLReason getRevocationReason() {
        return reason;
    }

    private void readObject(ObjectInputStream stream) throws IOException, ClassNotFoundException {
        stream.defaultReadObject();

        int size = stream.readInt();
        extensions = new HashMap<String, Extension>(size);
        for (int i = 0; i < size; i++) {
            String oid = (String) stream.readObject();
            boolean critical = stream.readBoolean();
            int valueLen = stream.readInt();
            byte[] value = new byte[valueLen];
            stream.read(value);
            extensions.put(oid,
                    new org.apache.harmony.security.x509.Extension(oid, critical, value));
        }
    }

    private void writeObject(ObjectOutputStream stream) throws IOException {
        stream.defaultWriteObject();

        stream.writeInt(extensions.size());
        for (Extension e : extensions.values()) {
            stream.writeObject(e.getId());
            stream.writeBoolean(e.isCritical());
            byte[] value = e.getValue();
            stream.writeInt(value.length);
            stream.write(value);
        }
    }
}
