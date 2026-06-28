package com.expensetracker.discover;

import com.expensetracker.config.Account;
import com.expensetracker.config.AccountType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatementDiscoveryTest {

    private static Account bank(String label, String pattern) {
        return new Account(label, AccountType.BANK, "FMT", pattern, null, null, false);
    }

    @Test
    void unknownFileIsWarnedAndSkipped_notFailed(@TempDir Path dir) throws Exception {
        Files.createFile(dir.resolve("202606_HDFC.pdf"));
        Files.createFile(dir.resolve("202604_BANDHAN_huf.pdf"));      // matches no configured account
        List<Account> accounts = List.of(bank("HDFC", ".*_HDFC\\.pdf"));

        List<StatementDiscovery.Match> matches = new StatementDiscovery().discover(dir, accounts);

        assertEquals(1, matches.size());                              // the unknown file is skipped, no abort
        assertEquals("HDFC", matches.get(0).account().label());
        assertEquals("202606_HDFC.pdf", matches.get(0).file().getFileName().toString());
    }

    @Test
    void multipleMatchesStillAbort(@TempDir Path dir) throws Exception {
        Files.createFile(dir.resolve("202606_HDFC.pdf"));
        List<Account> accounts = List.of(bank("HDFC", "_HDFC"), bank("OTHER", "202606"));   // both match

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new StatementDiscovery().discover(dir, accounts));
        assertTrue(e.getMessage().contains("multiple accounts"), e.getMessage());
    }
}
