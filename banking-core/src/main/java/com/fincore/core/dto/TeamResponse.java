package com.fincore.core.dto;

import java.util.List;

public record TeamResponse(
        int maxMembers, List<TeamMemberResponse> members, List<TeamInviteResponse> invites) {}
