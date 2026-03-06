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
  created_by uuid not null references auth.users(id) on delete cascade default auth.uid(),
  is_public boolean not null default false,
  latitude double precision,
  longitude double precision,
  start_time timestamptz not null default now(),
  end_time timestamptz
);

alter table sessions add column if not exists created_by uuid references auth.users(id) on delete cascade default auth.uid();
alter table sessions add column if not exists is_public boolean not null default false;
alter table sessions add column if not exists latitude double precision;
alter table sessions add column if not exists longitude double precision;

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

create policy "authenticated can read profiles"
on users_profile for select
to authenticated
using (true);

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
using (
  sessions.is_public = true
  or sessions.created_by = auth.uid()
  or exists (
    select 1 from session_members sm
    where sm.session_id = sessions.id
      and sm.user_id = auth.uid()
      and sm.left_at is null
  )
);

create policy "authenticated can create sessions"
on sessions for insert
to authenticated
with check (sessions.created_by = auth.uid());

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

create or replace function create_merged_session(source_session_ids uuid[], merged_name text)
returns table(session_id uuid)
language plpgsql
security definer
set search_path = public
as $$
declare
  new_session_id uuid;
begin
  if source_session_ids is null or array_length(source_session_ids, 1) < 2 then
    raise exception 'At least two source sessions are required';
  end if;

  if not exists (
    select 1
    from session_members sm
    where sm.user_id = auth.uid()
      and sm.left_at is null
      and sm.session_id = any(source_session_ids)
  ) then
    raise exception 'Not authorized for source sessions';
  end if;

  insert into sessions(id, name, start_time)
  values (gen_random_uuid(), coalesce(nullif(trim(merged_name), ''), 'Merged session'), now())
  returning id into new_session_id;

  insert into session_members(session_id, user_id, joined_at, left_at)
  select distinct
    new_session_id,
    sm.user_id,
    now(),
    null
  from session_members sm
  where sm.session_id = any(source_session_ids)
  on conflict (session_id, user_id) do nothing;

  insert into position_stream(user_id, session_id, timestamp, x, y, z, speed, segment_type)
  select
    ps.user_id,
    new_session_id,
    ps.timestamp,
    ps.x,
    ps.y,
    ps.z,
    ps.speed,
    ps.segment_type
  from position_stream ps
  where ps.session_id = any(source_session_ids);

  return query select new_session_id;
end;
$$;

grant execute on function create_merged_session(uuid[], text) to authenticated;

create or replace function rename_session_for_member(session_id uuid, new_name text)
returns void
language plpgsql
security definer
set search_path = public
as $$
begin
  if not exists (
    select 1 from session_members sm
    where sm.session_id = rename_session_for_member.session_id
      and sm.user_id = auth.uid()
      and sm.left_at is null
  ) then
    raise exception 'Not authorized for session';
  end if;

  update sessions
  set name = coalesce(nullif(trim(new_name), ''), name)
  where id = rename_session_for_member.session_id;
end;
$$;

grant execute on function rename_session_for_member(uuid, text) to authenticated;

create or replace function delete_sessions_for_member(session_ids uuid[])
returns void
language plpgsql
security definer
set search_path = public
as $$
begin
  if session_ids is null or array_length(session_ids, 1) is null then
    return;
  end if;

  delete from sessions s
  where s.id = any(session_ids)
    and exists (
      select 1 from session_members sm
      where sm.session_id = s.id
        and sm.user_id = auth.uid()
    );
end;
$$;

grant execute on function delete_sessions_for_member(uuid[]) to authenticated;
