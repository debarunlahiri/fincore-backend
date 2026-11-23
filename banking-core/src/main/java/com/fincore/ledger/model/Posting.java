package com.fincore.ledger.model;

import com.fincore.account.entity.Account;
import com.fincore.ledger.entity.Direction;
import java.math.BigDecimal;

/** A requested movement on a locked account. Postings of one journal must balance per currency. */
public record Posting(Account account, Direction direction, BigDecimal amount) {}
