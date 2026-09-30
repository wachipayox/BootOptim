"""Require complete concrete-type attribution; diagnostic wall is not a speedup."""
import re


def validate(text):
    scopes = []
    for line in text.splitlines():
        if 'BOOTOPTIM_DISCOVERY_WORK ' in line:
            scopes.append([dict(re.findall(r'(\w+)=([^\s]+)', line.split('BOOTOPTIM_DISCOVERY_WORK ', 1)[1])), []])
        elif 'BOOTOPTIM_DEPENDENCY_TYPE ' in line:
            if not scopes:
                raise ValueError('Orphan dependency type record')
            scopes[-1][1].append(dict(re.findall(r'(\w+)=([^\s]+)', line.split('BOOTOPTIM_DEPENDENCY_TYPE ', 1)[1])))
    if not scopes:
        raise ValueError('Missing discovery detail marker')
    for detail, types in scopes:
        if detail.get('success') != 'true' or detail.get('available') != 'true' or not types:
            raise ValueError('Incomplete or failed discovery attribution')
        if len({t['class'] for t in types}) != len(types):
            raise ValueError('Duplicated concrete-type budget')
        for t in types:
            if not 0 < int(t['distinct_models']) <= int(t['calls']) or float(t['dependency_wall_ms']) < 0:
                raise ValueError('Invalid identity census or type wall')
        if sum(int(t['calls']) for t in types) != int(detail['dependency_calls']):
            raise ValueError('Type counts do not reconstruct dependency observations')
        if abs(sum(float(t['dependency_wall_ms']) for t in types) - float(detail['dependencies_ms'])) > .001:
            raise ValueError('Type wall does not reconstruct exclusive dependency bucket')
    return len(scopes)
