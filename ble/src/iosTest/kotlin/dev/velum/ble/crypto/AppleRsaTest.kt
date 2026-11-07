package dev.velum.ble.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals

class AppleRsaTest {
    // Disposable test-only RSA key; ciphertext generated independently with OpenSSL-backed Python cryptography.
    private val privatePem = """
-----BEGIN PRIVATE KEY-----
MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQCim1BWije0kT4R
cODZB2nXuFS3eR48PkX/MF6Q/LrDkStWDIjZjHhkzhC9BP5U4uWzhwgzdVp930zt
wdis2O+yD4NLPT/QwP8OM4o5R6b7f88g8uEP6fK5pfgoZfF39EXJgRwTfB9Fzw/a
T+3VQyN/dS+eWxOjxlrKt3JTto1J8B80bFcyVc9F8rixV3BG3uaiBlpi1E9H+AXj
8/9A/ndPW3uOwZNO5WMMbb9dQ6JTo+FmsOIQU7jCCSzAM0FccDorlRIWycC0Tfuo
fuD7phfXAheIX5fWEom6oNw7ZlUvkrBQv5AqmD4i4DSDv70MOTNL1Yd0gqZsgwZC
Wd8jQLFpAgMBAAECggEACz1AwvCMC+Yvo/k5Q2sGovdd3K4pm7iisjIz0SH030xn
Ekwsv9u8nRwcrlH8ad3iimOKmrLxeaqP+M2Zpk3a2IQHu1E9Glbiska39sBX/4F8
rb539beCFFivU8yDEZtf4P8Uc5jUWQkIQ5rbea6JszdEfrW1IHB3CVS/eV+QecQb
uluWIpS+v0flh8lydeVzm6ZOUN9sDz5RAlKjI5QNATBbLf0NhHl9asxbkJ36SWQe
mMoDhrBzeLRx20eo9lQY9jbUq6L6EBeBeG+Y4tR1qoZFtBlQTEXEL4kS/ZosUUn8
yaKuvk+jWi8POzCF/1ETBsQYYZOqCw2uNi+ocDe+4QKBgQDdD/xMVQ+NyzwLaGV4
cMSHdY2+rSbofif2ATjqaQ1KaskOJzoYfMCf3MY6GM77Xmh6yHhuztwZWFRCDSxc
QivEqyyOPoQ5JMh7XSvkNjq+dTxzfBZM8XbwQblEMo811W6QqbMyJdPF3eQTG5hQ
oBQpjfbACau7rAx3IVPwYQk+SQKBgQC8TkEQ2jcgex9LXyRoH7BWc2TETf8fyJyc
/hUL3Bs3hJzOGBCcJCbRMws2HxOWmPqNU/n4e3yoT+RJMt/GFZSZpxqGi5Eu28aZ
9jmPKO8+ywoStYecnv5RMgWUenOtURFLDh+5FXBKXLYkoNDXTlKVNJA1pQgtEL4I
sMB/n9paIQKBgFlW+ooG3nBZsHyTxl50hJiSI6Tp6Y2QXqoHOG9T4XEv0u2LYOlA
kcCJwvr7x7rDHspwdmuh4kXD8tl25XSrsXCcFTevgFRvzy48K17ho8ARNUse3Qxf
kcfrZ+pt3H7PMQ3551fe/cU7tc8bGeWLrFpW62AVCHiiQa9rorbFIK0xAoGAbiB1
/R0h0GUxUCs8Z4jg4C5saEIVSslQjqAqPt1jLXzpF78LN8YMzZxEmkpicljVb4IF
V0UhCm5u3EBcjsRgO9x2rulA516xL6nIz+jowpiMM5Tm0rpJkcLncIaM0Hfw3SxN
cTkQiuzduV4zxTeRJKGxFS6HMZPxD7QPYJRkZwECgYEAiEx/bhEMmMkaYbTq7OTb
4h4UvHR3hfqskWXynLF0CeOYoW0wyuNsp94ogI9gCO48gWf6kCEQJJV/5yrFCdpx
L/E2MHuXWHwoHRrektZmaWO+IEFO1ydTMCRMzzF/ynefliiU6Xc/KO/+sxJ3mdiU
sp/53jBFf6YKUl5g3zgwUz4=
-----END PRIVATE KEY-----
""".trimIndent()
    private val publicPem = """
-----BEGIN PUBLIC KEY-----
MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAoptQVoo3tJE+EXDg2Qdp
17hUt3kePD5F/zBekPy6w5ErVgyI2Yx4ZM4QvQT+VOLls4cIM3Vafd9M7cHYrNjv
sg+DSz0/0MD/DjOKOUem+3/PIPLhD+nyuaX4KGXxd/RFyYEcE3wfRc8P2k/t1UMj
f3UvnlsTo8ZayrdyU7aNSfAfNGxXMlXPRfK4sVdwRt7mogZaYtRPR/gF4/P/QP53
T1t7jsGTTuVjDG2/XUOiU6PhZrDiEFO4wgkswDNBXHA6K5USFsnAtE37qH7g+6YX
1wIXiF+X1hKJuqDcO2ZVL5KwUL+QKpg+IuA0g7+9DDkzS9WHdIKmbIMGQlnfI0Cx
aQIDAQAB
-----END PUBLIC KEY-----
""".trimIndent()
    @Test fun unwraps_known_pkcs8_ciphertext_and_roundtrips_public_spki() {
        val cipher = createSessionCipher()
        val expected = "known content key".encodeToByteArray()
        val encrypted = "66f6bbd378054481e6e2f1a9ef852956427e94382eace23be11e51dc11705763799417dcacac501dffdaad4c447510784d03a81081b6ebb016426a6de569e80e1b32bf1263d5e962d6e8da580e725a9cf400f89d9056ffd6a37b1f789f1f7fd674ff6b47747e6d900aaffbf4e347a91398f515558050c677e23776866459cbfdda048a6fb2ba738b010ce07555ebfb7119900d15373b634e06a8d5c5b0ea7d7973c9375fd712d8707f3f10ebe5b9d7038981bff923e28863fdc01da1f1ca307182018d7c645511d1a7b31f9d36db5a683aa291f086d6a137196f579bdd2bd57ef1f4e14c1695eed332a99cf2aa6df266cf842ec8a86a89d6d5d4e748183dfafa".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertContentEquals(expected, createFileDecryptor().unwrapContentKey(privatePem, encrypted))
        assertContentEquals(expected, cipher.rsaDecryptPkcs1(privatePem, cipher.rsaEncryptPkcs1(publicPem, expected)))
    }
}
