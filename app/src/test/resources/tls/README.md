# Публичные сертификаты для проверки pins

Это открытые корневые сертификаты, а не приватные ключи или дополнительные доверенные центры приложения. Тесты используют их только для проверки соответствия SHA-256 SPKI константам; настоящий TLS проверяется отдельно локальным сервером.

Источники, проверенные 2026-11-10:

- `isrg-root-yr.pem`, `isrg-root-ye.pem`: [Let’s Encrypt](https://letsencrypt.org/certificates/), файлы [YR](https://letsencrypt.org/certs/gen-y/root-yr.pem) и [YE](https://letsencrypt.org/certs/gen-y/root-ye.pem).
- `sectigo-root-e46.pem`, `sectigo-root-r46.pem`: публичные корни из системного доверенного хранилища OpenSSL; имена и ключ E46 сверены с проверенной цепочкой `github.com`. [Описание корней Sectigo](https://support.sectigo.com/Com_KnowledgeDetailPage?Id=kA0Uj0000004IrB).
- `digicert-global-root-g2.pem`, `digicert-global-root-g3.pem`: [DigiCert G2](https://cacerts.digicert.com/DigiCertGlobalRootG2.crt.pem) и [DigiCert G3](https://cacerts.digicert.com/DigiCertGlobalRootG3.crt.pem).
- `sslcom-root-ecc.pem`, `sslcom-root-rsa.pem`: [SSL.com ECC](https://repo-certs.ssl.com/repo/certs/SSLcomRootCertificationAuthorityECC.pem) и [SSL.com RSA](https://repo-certs.ssl.com/repo/certs/SSLcomRootCertificationAuthorityRSA.pem) из [репозитория центра](https://www.ssl.com/repository/). ECC соответствует проверенной цепочке `cloudflare-dns.com`, RSA — резервный ключ.
