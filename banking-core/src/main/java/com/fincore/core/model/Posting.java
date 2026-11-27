package com.fincore.core.model;

import com.fincore.core.entity.Account;
import com.fincore.core.entity.Direction;
import java.math.BigDecimal;

/** A requested movement on a locked account. Postings of one journal must balance per currency. */
public record Posting(Account account, Direction direction, BigDecimal amount) {}
