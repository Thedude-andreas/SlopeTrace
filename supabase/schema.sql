-- Supabase MVP schema for SlopeTrace.
create extension if not exists "pgcrypto";

create table if not exists users_profile (
  id uuid primary key references auth.users(id) on delete cascade,
  name text not null,
  color text not null default '#00AEEF'
);

create table if not exists sessions (
  id uuid primary key default gen_random_uuid(),
  name text not null,
  start_time timestamptz not null default now(),
  end_time timestamptz
);

create table if not exists session_members (
  session_id uuid not null references sessions(id) on delete cascade,
  user_id uuid not null references auth.users(id) on delete cascade,
  joined_at timestamptz not null default now(),
  left_at timestamptz,
  primary key (session_id, user_id)
);

create table if not exists position_stream (
  id bigint generated always as identity primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  session_id uuid not null references sessions(id) on delete cascade,
  timestamp timestamptz not null,
  x double precision not null,
  y double precision not null,
  z double precision not null,
  speed double precision not null,
  segment_type text not null check (segment_type in ('lift', 'downhill', 'unknown'))
);

alter table users_profile enable row level security;
alter table sessions enable row level security;
alter table session_members enable row level security;
alter table position_stream enable row level security;

create policy "users can read own profile"
on users_profile for select
using (id = auth.uid());

create policy "users can create own profile"
on users_profile for insert
with check (id = auth.uid());

create policy "users can update own profile"
on users_profile for update
using (id = auth.uid())
with check (id = auth.uid());

create policy "authenticated can view sessions"
on sessions for select
to authenticated
using (true);

create policy "authenticated can create sessions"
on sessions for insert
to authenticated
with check (true);

create policy "members can view session_members"
on session_members for select
using (user_id = auth.uid());

create policy "users can join sessions as self"
on session_members for insert
with check (user_id = auth.uid());

create policy "users can update own membership"
on session_members for update
using (user_id = auth.uid())
with check (user_id = auth.uid());

create policy "members can view session positions"
on position_stream for select
using (
  exists (
    select 1 from session_members sm
    where sm.session_id = position_stream.session_id
      and sm.user_id = auth.uid()
      and sm.left_at is null
  )
);

create policy "members can insert own session positions"
on position_stream for insert
with check (
  user_id = auth.uid() and
  exists (
    select 1 from session_members sm
    where sm.session_id = position_stream.session_id
      and sm.user_id = auth.uid()
      and sm.left_at is null
  )
);
