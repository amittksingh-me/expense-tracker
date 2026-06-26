# Statement text fixtures

Drop one `pdftotext -layout` output file per bank here, e.g.:

```
bank1-sample.txt
bank2-sample.txt
bank3-sample.txt
bank4-sample.txt
```

Generate each with:

```bash
qpdf --password='YOUR_PASSWORD' --decrypt statement.pdf decrypted.pdf
pdftotext -layout decrypted.pdf src/test/resources/fixtures/bank1-sample.txt
rm decrypted.pdf
```

These files contain real statement data — keep them local. If this folder is ever put under
git, add it to `.gitignore`.
